package com.blacksingularities.opensync.engine

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.blacksingularities.opensync.data.Job
import com.blacksingularities.opensync.data.RunKind
import com.blacksingularities.opensync.data.RunRecord
import com.blacksingularities.opensync.data.RunStatus
import com.blacksingularities.opensync.data.Store
import com.blacksingularities.opensync.repo.FileEntry
import com.blacksingularities.opensync.repo.Repository
import com.blacksingularities.opensync.repo.RetentionPolicy
import com.blacksingularities.opensync.sources.safeName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Weryfikacja, porządkowanie i przywracanie. */
class MaintenanceEngine(private val context: Context, private val job: Job) {
    private val store = Store.get(context)
    private var progress = Progress(job.id, job.name, "Łączenie")

    private fun report(phase: String, fraction: Float = -1f, detail: String = "") {
        progress = progress.copy(phase = phase, fraction = fraction, detail = detail)
        Operations.set(progress)
    }

    private suspend fun guarded(kind: RunKind, body: suspend (Repository, RunRecord) -> RunRecord): RunRecord = withContext(Dispatchers.IO) {
        val base = RunRecord(jobId = job.id, jobName = job.name, kind = kind, start = System.currentTimeMillis())
        progress = Progress(job.id, "${kind.label}: ${job.name}", "Łączenie")
        try {
            RepoSession.open(context, job, createIfMissing = false) { report(it) }.use { s ->
                body(s.repo, base).copy(end = System.currentTimeMillis())
            }
        } catch (e: CancellationException) {
            base.copy(end = System.currentTimeMillis(), status = RunStatus.CANCELLED, message = "Przerwano")
        } catch (e: RepoSession.SkipException) {
            base.copy(end = System.currentTimeMillis(), status = RunStatus.SKIPPED, message = e.message ?: "")
        } catch (e: Exception) {
            base.copy(end = System.currentTimeMillis(), status = RunStatus.FAILED, message = friendly(e))
        } finally {
            Operations.clear(job.id)
        }
    }

    /** Weryfikacja integralności; [samplePct] = 100 oznacza pobranie i sprawdzenie wszystkich fragmentów. */
    suspend fun check(samplePct: Int): RunRecord = guarded(RunKind.CHECK) { repo, base ->
        val r = repo.check(samplePct) { phase, f -> report(phase, f) }
        val problems = buildList {
            if (r.unreadableSnapshots.isNotEmpty()) add("Nieczytelne kopie: ${r.unreadableSnapshots.size}")
            if (r.missing.isNotEmpty()) add("Brakujące fragmenty: ${r.missing.size}")
            if (r.corrupted.isNotEmpty()) add("Uszkodzone fragmenty: ${r.corrupted.size}")
        }
        val summary = "Kopie: ${r.snapshots} · fragmenty: ${r.referencedBlobs} · sprawdzone: ${r.verified}" +
            if (r.orphans > 0) " · nieużywane: ${r.orphans}" else ""
        base.copy(
            status = if (r.ok) RunStatus.OK else RunStatus.FAILED,
            message = if (r.ok) summary else problems.joinToString(" · "),
            warnings = if (r.ok) emptyList() else listOf(summary) + r.unreadableSnapshots.map { "Kopia $it" } + r.missing.take(20).map { "Brak $it" } + r.corrupted.take(20).map { "Uszkodzony $it" },
        )
    }

    /** Ręczne zastosowanie retencji i zwolnienie miejsca. */
    suspend fun prune(): RunRecord = guarded(RunKind.PRUNE) { repo, base ->
        repo.lock(store.deviceId, store.deviceName)
        val refs = repo.listSnapshots().filter { it.jobId == job.id }
        val d = RetentionPolicy.apply(refs, job.retention, System.currentTimeMillis(), repo.pins())
        val r = repo.prune(d.remove.map { it.id }) { phase, f -> report(phase, f) }
        base.copy(message = "Usunięte kopie: ${r.removedSnapshots} · zwolnione fragmenty: ${r.removedBlobs}", removedSnapshots = r.removedSnapshots)
    }

    /** Przywraca wybrane pliki z kopii do folderu wskazanego przez użytkownika, z zachowaniem struktury. */
    suspend fun restore(snapshotId: String, paths: Set<String>?, target: Uri): RunRecord = guarded(RunKind.RESTORE) { repo, base ->
        val snap = repo.loadSnapshot(snapshotId)
        val files = snap.files.filter { paths == null || it.path in paths || paths.any { p -> it.path.startsWith("$p/") } }
        val root = DocumentFile.fromTreeUri(context, target) ?: error("Folder docelowy niedostępny")
        val dirs = HashMap<String, DocumentFile>()
        fun dir(path: String): DocumentFile {
            if (path.isEmpty()) return root
            dirs[path]?.let { return it }
            val parent = dir(path.substringBeforeLast('/', ""))
            val name = path.substringAfterLast('/')
            val d = parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name) ?: error("Nie można utworzyć $path")
            dirs[path] = d
            return d
        }
        val total = files.sumOf { it.size }
        var done = 0L
        val warnings = ArrayList<String>()
        val folderName = "OpenSync " + snap.id.substringBefore('_').let { "${it.take(4)}-${it.substring(4, 6)}-${it.substring(6, 8)} ${it.substring(9, 11)}.${it.substring(11, 13)}" }
        files.forEachIndexed { i, f ->
            currentCoroutineContext().ensureActive()
            report("Przywracanie ${i + 1}/${files.size}", if (total > 0) done.toFloat() / total else 0f, f.path)
            try {
                val parent = dir(safeName(folderName) + "/" + f.path.substringBeforeLast('/', "").trim('/'))
                val name = f.path.substringAfterLast('/')
                parent.findFile(name)?.delete()
                val out = parent.createFile("application/octet-stream", name) ?: error("Nie można utworzyć pliku")
                context.contentResolver.openOutputStream(out.uri, "wt")?.use { os -> repo.restoreTo(f, os) { done += it } }
                    ?: error("Nie można zapisać pliku")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnings += "${f.path}: ${friendly(e)}"
            }
        }
        base.copy(
            status = if (warnings.isEmpty()) RunStatus.OK else RunStatus.WARN,
            message = "Przywrócono ${files.size - warnings.size} z ${files.size} plików do folderu „$folderName”",
            files = files.size, bytesTotal = total, snapshotId = snapshotId, warnings = warnings.take(100),
        )
    }

    companion object {
        /** Pobiera jeden plik z kopii do pamięci podręcznej (podgląd, udostępnienie, import). */
        suspend fun extract(context: Context, job: Job, entry: FileEntry): File = withContext(Dispatchers.IO) {
            RepoSession.open(context, job, createIfMissing = false).use { s ->
                val dir = File(context.cacheDir, "restore").apply { mkdirs() }
                dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
                val f = File(dir, entry.path.substringAfterLast('/'))
                f.outputStream().use { s.repo.restoreTo(entry, it) }
                f
            }
        }
    }
}
