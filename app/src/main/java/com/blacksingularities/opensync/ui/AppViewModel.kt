package com.blacksingularities.opensync.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blacksingularities.opensync.backend.Backends
import com.blacksingularities.opensync.backend.SftpBackend
import com.blacksingularities.opensync.backend.WakeOnLan
import com.blacksingularities.opensync.data.DestType
import com.blacksingularities.opensync.data.Destination
import com.blacksingularities.opensync.data.Job
import com.blacksingularities.opensync.data.Secrets
import com.blacksingularities.opensync.data.Store
import com.blacksingularities.opensync.engine.MaintenanceEngine
import com.blacksingularities.opensync.engine.RepoSession
import com.blacksingularities.opensync.engine.friendly
import com.blacksingularities.opensync.repo.FileEntry
import com.blacksingularities.opensync.repo.Snapshot
import com.blacksingularities.opensync.repo.SnapshotDiff
import com.blacksingularities.opensync.repo.SnapshotRef
import com.blacksingularities.opensync.update.UpdateManager
import com.blacksingularities.opensync.update.UpdateState
import com.blacksingularities.opensync.update.isNewer
import com.blacksingularities.opensync.work.BackupWorker
import com.blacksingularities.opensync.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class EditJob(val jobId: String?) : Screen
    data object Destinations : Screen
    data class EditDestination(val destId: String?, val returnToJob: Boolean = false) : Screen
    data class Snapshots(val jobId: String) : Screen
    data class Browse(val jobId: String, val snapshotId: String) : Screen
    data class Diff(val jobId: String, val olderId: String, val newerId: String) : Screen
    data object History : Screen
    data object Settings : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    val store = Store.get(ctx)

    // ---- Nawigacja ------------------------------------------------------------------------
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()
    val canGoBack get() = stack.size > 1

    fun go(s: Screen) { stack += s }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }

    var toast by mutableStateOf<String?>(null)

    // ---- Zadania i cele -------------------------------------------------------------------
    fun saveJob(job: Job, scheduleChanged: Boolean) {
        store.saveJob(job)
        Scheduler.apply(ctx, job, reset = scheduleChanged)
    }

    fun deleteJob(id: String) {
        Scheduler.remove(ctx, id)
        store.deleteJob(id)
    }

    fun saveDestination(d: Destination) = store.saveDestination(d)

    fun deleteDestination(id: String): Boolean {
        if (store.jobs.value.any { it.destinationId == id }) {
            toast = "To miejsce jest używane przez zadanie – najpierw zmień zadanie"
            return false
        }
        store.deleteDestination(id)
        return true
    }

    fun runNow(jobId: String, op: String = BackupWorker.OP_BACKUP) {
        Scheduler.runNow(ctx, jobId, op)
        toast = when (op) {
            BackupWorker.OP_CHECK -> "Weryfikacja uruchomiona"
            BackupWorker.OP_CHECK_FULL -> "Pełna weryfikacja uruchomiona – może potrwać"
            BackupWorker.OP_PRUNE -> "Porządkowanie uruchomione"
            else -> "Kopia uruchomiona"
        }
    }

    fun cancel(jobId: String) = Scheduler.cancel(ctx, jobId)

    fun nextRun(jobId: String) = Scheduler.nextRun(ctx, jobId)

    // ---- Test połączenia ------------------------------------------------------------------
    var testing by mutableStateOf(false)
    var testResult by mutableStateOf<Pair<Boolean, String>?>(null)

    /** Łączy się, zapisuje i usuwa plik próbny; dla SFTP zapamiętuje klucz serwera. */
    fun testDestination(d: Destination, onHostKey: (String) -> Unit) {
        testing = true
        testResult = null
        viewModelScope.launch {
            testResult = withContext(Dispatchers.IO) {
                runCatching {
                    RepoSession.ensureReachable(ctx, d) {}
                    Backends.open(ctx, d, "").use { b ->
                        if (b is SftpBackend && d.hostKey.isBlank()) withContext(Dispatchers.Main) { onHostKey(b.seenHostKey) }
                        val name = ".opensync-test-" + System.currentTimeMillis()
                        b.write(name, "OpenSync".toByteArray())
                        val ok = b.read(name).toString(Charsets.UTF_8) == "OpenSync"
                        b.delete(name)
                        if (!ok) error("Odczytany plik różni się od zapisanego")
                        val free = b.freeSpace()
                        true to ("Połączenie działa, zapis i odczyt OK" + if (free > 0) " · wolne ${formatBytes(free)}" else "")
                    }
                }.getOrElse { false to friendly(it) }
            }
            testing = false
        }
    }

    fun wake(d: Destination) {
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { WakeOnLan.send(d.wolMac, d.wolBroadcast.ifBlank { RepoSession.broadcastAddress(ctx) }) }
            withContext(Dispatchers.Main) { toast = r.fold({ "Wysłano pakiet Wake-on-LAN" }, { friendly(it) }) }
        }
    }

    // ---- Wykrywanie serwerów w sieci (mDNS / NSD) -----------------------------------------
    data class Found(val name: String, val host: String, val port: Int, val type: DestType)

    val found = mutableStateListOf<Found>()
    var discovering by mutableStateOf(false)
    private var discoveryJob: CoJob? = null

    @Suppress("DEPRECATION")
    fun discover() {
        if (discovering) return
        found.clear()
        discovering = true
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val types = listOf("_smb._tcp." to DestType.SMB, "_sftp-ssh._tcp." to DestType.SFTP, "_ssh._tcp." to DestType.SFTP, "_webdav._tcp." to DestType.WEBDAV, "_webdavs._tcp." to DestType.WEBDAV)
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        for ((type, dt) in types) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    runCatching {
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                            override fun onServiceResolved(s: NsdServiceInfo) {
                                val host = s.host?.hostAddress ?: return
                                val f = Found(s.serviceName, host, s.port, dt)
                                viewModelScope.launch { if (found.none { it.host == f.host && it.type == f.type }) found += f }
                            }
                        })
                    }
                }
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners += l }
        }
        discoveryJob = viewModelScope.launch {
            delay(10_000)
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            discovering = false
        }
    }

    // ---- Przeglądanie kopii ---------------------------------------------------------------
    var snapshotsLoading by mutableStateOf(false)
    var snapshotsError by mutableStateOf<String?>(null)
    var snapshots by mutableStateOf<List<SnapshotRef>>(emptyList())
    var pins by mutableStateOf<Set<String>>(emptySet())
    var showAllJobs by mutableStateOf(false)
    var repoInfo by mutableStateOf<String?>(null)
    private val loaded = HashMap<String, Snapshot>()
    var snapshotLoaded by mutableStateOf<Snapshot?>(null)
    var diff by mutableStateOf<SnapshotDiff?>(null)

    fun loadSnapshots(jobId: String) {
        val job = store.job(jobId) ?: return
        snapshotsLoading = true
        snapshotsError = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    RepoSession.open(ctx, job, createIfMissing = false).use { s ->
                        val refs = s.repo.listSnapshots()
                        val p = s.repo.pins()
                        val free = s.repo.freeSpace()
                        s.repo.refreshIndex()
                        Triple(refs, p, "Fragmenty: ${s.repo.blobCount}" + (if (free > 0) " · wolne na dysku: ${formatBytes(free)}" else "") + " · założone ${formatDate(s.repo.created)}")
                    }
                }
            }.onSuccess { (refs, p, info) ->
                snapshots = refs
                pins = p
                repoInfo = info
            }.onFailure {
                snapshotsError = if (it is com.blacksingularities.opensync.repo.Repository.NotARepositoryException) "Nie ma jeszcze żadnej kopii – uruchom pierwszą kopię." else friendly(it)
            }
            snapshotsLoading = false
        }
    }

    fun openSnapshot(jobId: String, snapshotId: String) {
        val job = store.job(jobId) ?: return
        loaded[snapshotId]?.let { snapshotLoaded = it; return }
        snapshotLoaded = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { RepoSession.open(ctx, job, false).use { it.repo.loadSnapshot(snapshotId) } }
            }.onSuccess { loaded[snapshotId] = it; snapshotLoaded = it }
                .onFailure { toast = friendly(it); back() }
        }
    }

    fun loadDiff(jobId: String, olderId: String, newerId: String) {
        val job = store.job(jobId) ?: return
        diff = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    RepoSession.open(ctx, job, false).use { s ->
                        SnapshotDiff.of(loaded[olderId] ?: s.repo.loadSnapshot(olderId), loaded[newerId] ?: s.repo.loadSnapshot(newerId))
                    }
                }
            }.onSuccess { diff = it }.onFailure { toast = friendly(it); back() }
        }
    }

    fun setPinned(jobId: String, id: String, pinned: Boolean) {
        val job = store.job(jobId) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { RepoSession.open(ctx, job, false).use { it.repo.setPinned(id, pinned) } } }
                .onSuccess { pins = if (pinned) pins + id else pins - id; toast = if (pinned) "Kopia przypięta – retencja jej nie usunie" else "Odpięto" }
                .onFailure { toast = friendly(it) }
        }
    }

    /** Usuwa jedną kopię i od razu zwalnia miejsce po nieużywanych fragmentach. */
    fun deleteSnapshot(jobId: String, id: String) {
        val job = store.job(jobId) ?: return
        viewModelScope.launch {
            toast = "Usuwanie kopii…"
            withContext(Dispatchers.IO) {
                runCatching {
                    RepoSession.open(ctx, job, false).use { s ->
                        s.repo.lock(store.deviceId, store.deviceName)
                        s.repo.prune(listOf(id))
                    }
                }
            }.onSuccess { snapshots = snapshots.filterNot { it.id == id }; toast = "Usunięto kopię i zwolniono ${it.removedBlobs} fragmentów" }
                .onFailure { toast = friendly(it) }
        }
    }

    fun restore(jobId: String, snapshotId: String, paths: Set<String>?, target: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val extra = HashMap<String, String>()
        extra[BackupWorker.KEY_SNAPSHOT] = snapshotId
        extra[BackupWorker.KEY_TARGET] = target.toString()
        if (paths != null) {
            val f = File(ctx.filesDir, "restore-${System.currentTimeMillis()}.txt")
            f.writeText(paths.joinToString("\n"))
            extra[BackupWorker.KEY_PATHS] = f.absolutePath
        }
        Scheduler.runNow(ctx, jobId, BackupWorker.OP_RESTORE, extra)
        toast = "Przywracanie uruchomione – postęp w powiadomieniu"
    }

    var extracting by mutableStateOf<String?>(null)

    /** Pobiera plik z kopii i otwiera go w aplikacji systemowej (vCard → Kontakty, .ics → Kalendarz…). */
    fun openFile(jobId: String, entry: FileEntry, share: Boolean = false) {
        val job = store.job(jobId) ?: return
        extracting = entry.path
        viewModelScope.launch {
            runCatching { MaintenanceEngine.extract(ctx, job, entry) }
                .onSuccess { f ->
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    val mime = mimeOf(f.name)
                    val intent = if (share) {
                        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    } else {
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    }
                    val chooser = Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), entry.path.substringAfterLast('/'))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(chooser) }.onFailure { toast = "Brak aplikacji do otwarcia tego pliku" }
                }
                .onFailure { toast = friendly(it) }
            extracting = null
        }
    }

    // ---- Hasło repozytorium ---------------------------------------------------------------
    fun changeRepoPassword(job: Job, newPassword: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { RepoSession.open(ctx, job, false).use { it.repo.changePassword(newPassword) } }
            }.onSuccess {
                // Wszystkie zadania z tym samym repozytorium dostają nowe hasło.
                store.jobs.value.filter { it.destinationId == job.destinationId && it.repoPath.trim('/') == job.repoPath.trim('/') }
                    .forEach { store.saveJob(it.copy(repoPassword = Secrets.protect(newPassword))) }
                toast = "Hasło repozytorium zmienione"
                onDone(true)
            }.onFailure { toast = friendly(it); onDone(false) }
        }
    }

    // ---- Ustawienia -----------------------------------------------------------------------
    fun exportConfig(target: Uri) {
        runCatching { ctx.contentResolver.openOutputStream(target, "wt")?.use { it.write(store.exportConfig().toByteArray()) } }
            .onSuccess { toast = "Zapisano ustawienia (bez haseł)" }
            .onFailure { toast = friendly(it) }
    }

    fun importConfig(source: Uri) {
        runCatching {
            val text = ctx.contentResolver.openInputStream(source)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Nie można odczytać pliku")
            store.importConfig(text)
        }.onSuccess { (d, j) ->
            Scheduler.reconcile(ctx, reset = true)
            toast = "Wczytano: miejsca $d, zadania $j. Uzupełnij hasła."
        }.onFailure { toast = friendly(it) }
    }

    // ---- Aktualizacje ---------------------------------------------------------------------
    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
    var updateBannerHidden by mutableStateOf(false)

    init {
        if (store.autoUpdateCheck) checkUpdate(silent = true)
    }

    fun checkUpdate(silent: Boolean) {
        if (update is UpdateState.Checking || update is UpdateState.Downloading || update is UpdateState.Installing) return
        update = UpdateState.Checking
        viewModelScope.launch {
            update = try {
                val info = UpdateManager.check()
                if (isNewer(info.version, UpdateManager.currentVersion)) {
                    updateBannerHidden = false
                    UpdateState.Available(info)
                } else {
                    UpdateState.UpToDate
                }
            } catch (e: Exception) {
                if (silent) UpdateState.Idle else UpdateState.Failed(e.message ?: "Błąd")
            }
        }
    }

    fun updateNow() {
        when (val u = update) {
            is UpdateState.Available -> {
                if (!UpdateManager.canInstall(ctx)) {
                    toast = "Zezwól OpenSync na instalowanie aplikacji"
                    UpdateManager.openInstallPermissionSettings(ctx)
                    return
                }
                viewModelScope.launch {
                    try {
                        update = UpdateState.Downloading(u.info, 0f)
                        val file = UpdateManager.download(ctx, u.info) { p -> update = UpdateState.Downloading(u.info, p) }
                        update = UpdateState.Installing(u.info)
                        UpdateManager.install(ctx, file)
                        update = UpdateState.Ready(u.info, file)
                    } catch (e: Exception) {
                        toast = e.message
                        update = UpdateState.Available(u.info)
                    }
                }
            }
            is UpdateState.Ready -> viewModelScope.launch {
                runCatching { UpdateManager.install(ctx, u.file) }.onFailure { toast = it.message }
            }
            else -> {}
        }
    }

    val updatePending: Boolean
        get() = update is UpdateState.Available || update is UpdateState.Downloading || update is UpdateState.Ready || update is UpdateState.Installing
}

fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic", "heif" -> "image/heif"
    "gif" -> "image/gif"
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "mp3" -> "audio/mpeg"
    "m4a", "aac" -> "audio/mp4"
    "ogg", "opus" -> "audio/ogg"
    "flac" -> "audio/flac"
    "pdf" -> "application/pdf"
    "vcf" -> "text/x-vcard"
    "ics" -> "text/calendar"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "txt", "log", "md" -> "text/plain"
    "apk" -> "application/vnd.android.package-archive"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}
