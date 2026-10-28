package com.blacksingularities.opensync.engine

import android.content.Context
import com.blacksingularities.opensync.BuildConfig
import com.blacksingularities.opensync.data.Job
import com.blacksingularities.opensync.data.RunKind
import com.blacksingularities.opensync.data.RunRecord
import com.blacksingularities.opensync.data.RunStatus
import com.blacksingularities.opensync.data.SourceType
import com.blacksingularities.opensync.data.Store
import com.blacksingularities.opensync.repo.FileEntry
import com.blacksingularities.opensync.repo.Repository
import com.blacksingularities.opensync.repo.RetentionPolicy
import com.blacksingularities.opensync.repo.Snapshot
import com.blacksingularities.opensync.sources.CollectContext
import com.blacksingularities.opensync.sources.Item
import com.blacksingularities.opensync.sources.Sources
import com.blacksingularities.opensync.sources.globMatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Wykonanie kopii zadania: zebranie plików ze źródeł, podział na fragmenty, deduplikacja,
 * szyfrowanie i wysyłka, zapis manifestu, a na końcu retencja (usunięcie nadmiarowych kopii).
 */
class BackupEngine(private val context: Context, private val job: Job) {
    private val store = Store.get(context)
    private val warnings = ArrayList<String>()
    private val started = System.currentTimeMillis()
    private var progress = Progress(job.id, job.name, "Przygotowanie")

    private fun report(p: Progress) {
        progress = p
        Operations.set(p)
    }

    private fun warn(msg: String) {
        if (warnings.size < 200) warnings += msg
    }

    suspend fun run(): RunRecord = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "work/${job.id}").apply { deleteRecursively(); mkdirs() }
        var record = RunRecord(jobId = job.id, jobName = job.name, kind = RunKind.BACKUP, start = started)
        try {
            report(progress.copy(phase = "Sprawdzanie sieci"))
            checkNetwork()
            RepoSession.open(context, job, createIfMissing = true) { report(progress.copy(phase = it)) }.use { s ->
                val repo = s.repo
                report(progress.copy(phase = "Blokada repozytorium"))
                repo.lock(store.deviceId, store.deviceName)
                report(progress.copy(phase = "Odczyt listy fragmentów"))
                repo.refreshIndex { report(progress.copy(phase = "Odczyt listy fragmentów", fraction = it)) }

                report(progress.copy(phase = "Zbieranie danych", fraction = -1f))
                val items = collect(workDir)
                record = backup(repo, items, record)

                if (job.retention.enabled) {
                    val refs = repo.listSnapshots().filter { it.jobId == job.id.filter { c -> c.isLetterOrDigit() } }
                    val decision = RetentionPolicy.apply(refs, job.retention, System.currentTimeMillis(), repo.pins())
                    if (decision.remove.isNotEmpty()) {
                        val r = repo.prune(decision.remove.map { it.id }) { phase, f -> report(progress.copy(phase = phase, fraction = f, detail = "")) }
                        record = record.copy(removedSnapshots = r.removedSnapshots)
                    }
                }
            }
            val status = if (warnings.isEmpty()) RunStatus.OK else RunStatus.WARN
            record.copy(end = System.currentTimeMillis(), status = status, warnings = warnings.toList())
        } catch (e: CancellationException) {
            record.copy(end = System.currentTimeMillis(), status = RunStatus.CANCELLED, message = "Przerwano", warnings = warnings.toList())
        } catch (e: RepoSession.SkipException) {
            record.copy(end = System.currentTimeMillis(), status = RunStatus.SKIPPED, message = e.message ?: "", warnings = warnings.toList())
        } catch (e: Exception) {
            record.copy(end = System.currentTimeMillis(), status = RunStatus.FAILED, message = friendly(e), warnings = warnings.toList())
        } finally {
            workDir.deleteRecursively()
            Operations.clear(job.id)
        }
    }

    private fun checkNetwork() {
        val ssids = job.conditions.homeSsids.map { it.trim() }.filter { it.isNotEmpty() }
        if (ssids.isEmpty()) return
        val current = RepoSession.currentSsid(context)
            ?: throw RepoSession.SkipException("Nie rozpoznano sieci Wi-Fi (włącz lokalizację i nadaj uprawnienie)")
        if (ssids.none { it.equals(current, ignoreCase = false) }) throw RepoSession.SkipException("Sieć „$current” nie jest siecią domową")
    }

    private suspend fun collect(workDir: File): List<Item> {
        val items = ArrayList<Item>()
        val f = job.filters
        val maxBytes = if (f.maxFileMb > 0) f.maxFileMb * 1024L * 1024L else Long.MAX_VALUE
        val seen = HashSet<String>()
        for (type in SourceType.entries) {
            if (type !in job.sources) continue
            currentCoroutineContext().ensureActive()
            val source = Sources.all[type] ?: continue
            report(progress.copy(phase = "Zbieranie: ${type.label}", detail = "${items.size} plików"))
            val c = CollectContext(context, job, workDir, { warn("${type.label}: $it") }) { item ->
                when {
                    !seen.add(item.path) -> {}
                    f.skipHidden && item.path.split('/').any { it.startsWith(".") } -> {}
                    item.size > maxBytes -> warn("Pominięto (za duży): ${item.path}")
                    f.exclude.any { globMatches(it, item.path) } -> {}
                    else -> items += item
                }
            }
            runCatching { source.collect(c) }.onFailure {
                if (it is CancellationException) throw it
                warn("${type.label}: ${friendly(it)}")
            }
        }
        return items
    }

    private class Cached(val size: Long, val mtime: Long, val chunks: List<String>)

    private fun loadCache(repo: Repository): HashMap<String, Cached> {
        val map = HashMap<String, Cached>()
        val f = File(repo.cacheDir, "files.idx")
        if (!f.exists()) return map
        runCatching {
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                val c = a.getJSONArray(2)
                map[k] = Cached(a.getLong(0), a.getLong(1), (0 until c.length()).map { c.getString(it) })
            }
        }
        return map
    }

    private fun saveCache(repo: Repository, map: Map<String, Cached>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, JSONArray().put(v.size).put(v.mtime).put(JSONArray(v.chunks))) }
        val f = File(repo.cacheDir, "files.idx")
        val tmp = File(repo.cacheDir, "files.idx.tmp")
        tmp.writeText(o.toString())
        f.delete()
        tmp.renameTo(f)
    }

    private suspend fun backup(repo: Repository, items: List<Item>, base: RunRecord): RunRecord {
        val cache = loadCache(repo)
        val newCache = HashMap<String, Cached>()
        val chunkSize = job.chunkMb.coerceIn(1, 64) * 1024 * 1024
        val buf = ByteArray(chunkSize)
        val entries = ArrayList<FileEntry>(items.size)
        val bytesTotal = items.sumOf { it.size.coerceAtLeast(0) }
        var bytesDone = 0L
        var uploaded = 0L
        var bytesNew = 0L
        var changed = 0
        val t0 = System.currentTimeMillis()
        var lastReport = 0L
        val throttle = if (job.limitKbps > 0) job.limitKbps * 1024L else 0L

        items.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val now = System.currentTimeMillis()
            if (now - lastReport > 250 || index == 0) {
                lastReport = now
                val secs = ((now - t0) / 1000.0).coerceAtLeast(0.5)
                report(
                    progress.copy(
                        phase = "Kopiowanie", detail = item.path,
                        fraction = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else index.toFloat() / items.size,
                        filesDone = index, filesTotal = items.size, bytesDone = bytesDone, bytesTotal = bytesTotal,
                        uploaded = uploaded, bytesPerSec = (bytesDone / secs).toLong(),
                    ),
                )
            }
            val key = item.cacheKey
            val hit = key?.let { cache[it] }?.takeIf { c ->
                c.size == item.size && c.mtime == item.mtime && item.size >= 0 && c.chunks.all { repo.hasBlob(it) }
            }
            if (hit != null) {
                entries += FileEntry(item.path, item.size, item.mtime, hit.chunks)
                newCache[key] = hit
                bytesDone += item.size
                return@forEachIndexed
            }
            try {
                val chunks = ArrayList<String>()
                var size = 0L
                var fileUploaded = 0L
                item.open().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = readFully(input, buf)
                        if (n <= 0) break
                        val level = if (item.compressible) job.compression else 0
                        val (id, sent) = repo.putChunk(buf, 0, n, level)
                        chunks += id
                        size += n
                        bytesDone += n
                        if (sent > 0) {
                            fileUploaded += sent
                            uploaded += sent
                            bytesNew += n
                            if (throttle > 0) Thread.sleep((sent * 1000 / throttle).coerceAtMost(60_000))
                        }
                        if (n < buf.size) break
                    }
                }
                if (fileUploaded > 0) changed++
                entries += FileEntry(item.path, size, item.mtime, chunks)
                if (key != null) newCache[key] = Cached(size, item.mtime, chunks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("${item.path}: ${friendly(e)}")
            }
        }

        report(progress.copy(phase = "Zapisywanie kopii", fraction = 1f, detail = ""))
        if (entries.isEmpty() && items.isNotEmpty()) error("Nie udało się skopiować żadnego pliku")
        val snap = Snapshot(
            id = repo.newSnapshotId(started, job.id),
            time = started,
            jobId = job.id.filter { it.isLetterOrDigit() },
            jobName = job.name,
            device = store.deviceName,
            deviceId = store.deviceId,
            appVersion = BuildConfig.VERSION_NAME,
            tags = job.tags,
            sources = job.sources.map { it.name },
            files = entries,
            durationMs = System.currentTimeMillis() - started,
        )
        repo.saveSnapshot(snap)
        saveCache(repo, newCache)
        return base.copy(
            files = entries.size, changedFiles = changed, bytesTotal = entries.sumOf { it.size },
            bytesNew = bytesNew, bytesUploaded = uploaded, snapshotId = snap.id,
        )
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}

/** Zrozumiały komunikat błędu dla użytkownika. */
fun friendly(e: Throwable): String {
    val msg = e.message.orEmpty()
    return when {
        e is com.blacksingularities.opensync.crypto.Crypto.WrongPasswordException -> "Nieprawidłowe hasło szyfrowania repozytorium"
        e is java.net.UnknownHostException -> "Nie znaleziono serwera (${msg})"
        e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "Brak połączenia z serwerem"
        e is java.net.SocketTimeoutException -> "Przekroczono czas oczekiwania na serwer"
        msg.contains("STATUS_LOGON_FAILURE") -> "Serwer odrzucił login lub hasło"
        msg.contains("STATUS_BAD_NETWORK_NAME") -> "Nie ma takiego udziału na serwerze"
        msg.contains("STATUS_ACCESS_DENIED") -> "Brak uprawnień do zapisu w udziale"
        msg.contains("STATUS_DISK_FULL") -> "Brak miejsca na dysku NAS"
        msg.contains("Auth fail", true) || msg.contains("Exhausted available authentication methods") -> "Serwer SSH odrzucił login, hasło lub klucz"
        msg.isNotBlank() -> msg
        else -> e.javaClass.simpleName
    }
}
