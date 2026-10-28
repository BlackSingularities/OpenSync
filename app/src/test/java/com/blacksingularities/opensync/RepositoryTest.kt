package com.blacksingularities.opensync

import com.blacksingularities.opensync.backend.FileBackend
import com.blacksingularities.opensync.crypto.Crypto
import com.blacksingularities.opensync.repo.FileEntry
import com.blacksingularities.opensync.repo.Repository
import com.blacksingularities.opensync.repo.Snapshot
import com.blacksingularities.opensync.repo.SnapshotDiff
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class RepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun backend() = FileBackend(File(tmp.root, "nas"))
    private fun repo() = Repository(backend(), File(tmp.root, "cache-" + System.nanoTime()))

    private fun store(r: Repository, data: ByteArray, chunk: Int): List<String> {
        val ids = ArrayList<String>()
        var off = 0
        while (off < data.size) {
            val n = minOf(chunk, data.size - off)
            ids += r.putChunk(data, off, n, 6).first
            off += n
        }
        return ids
    }

    private fun snapshot(r: Repository, time: Long, files: List<FileEntry>) =
        Snapshot(r.newSnapshotId(time, "job1"), time, "job1", "Test", "Telefon", "dev", "test", emptyList(), emptyList(), files)
            .also { r.saveSnapshot(it) }

    private fun blobFiles() = File(tmp.root, "nas/data").walk().filter { it.isFile }.toList()

    @Test
    fun backupRestoreDedupPruneAndCheck() {
        val r = repo()
        r.init("tajne-haslo", 1000)
        val photo = Crypto.randomBytes(300_000)
        val notes = "notatka\n".repeat(10_000).toByteArray()

        val a = snapshot(
            r, 1_700_000_000_000,
            listOf(
                FileEntry("media/photo.jpg", photo.size.toLong(), 1, store(r, photo, 65536)),
                FileEntry("notes.txt", notes.size.toLong(), 1, store(r, notes, 65536)),
            ),
        )
        val blobsAfterFirst = blobFiles().size

        // Druga kopia z tym samym zdjęciem: nowe są tylko fragmenty zmienionej notatki.
        val notes2 = notes + "dopisek".toByteArray()
        val b = snapshot(
            r, 1_700_000_100_000,
            listOf(
                FileEntry("media/photo.jpg", photo.size.toLong(), 1, store(r, photo, 65536)),
                FileEntry("notes.txt", notes2.size.toLong(), 2, store(r, notes2, 65536)),
            ),
        )
        assertTrue(blobFiles().size - blobsAfterFirst <= 2)

        // Nowe połączenie (np. inne urządzenie) otwiera repozytorium tym samym hasłem.
        val r2 = repo()
        r2.open("tajne-haslo")
        assertEquals(listOf(b.id, a.id), r2.listSnapshots().map { it.id })
        val restored = ByteArrayOutputStream()
        r2.restoreTo(r2.loadSnapshot(a.id).files.first { it.path == "media/photo.jpg" }, restored)
        assertArrayEquals(photo, restored.toByteArray())

        val diff = SnapshotDiff.of(r2.loadSnapshot(a.id), r2.loadSnapshot(b.id))
        assertEquals(listOf("notes.txt"), diff.changed.map { it.path })
        assertTrue(r2.check(100).ok)

        // Usunięcie starszej kopii zwalnia tylko nieużywane fragmenty.
        r2.lock("dev", "Telefon")
        val pruned = r2.prune(listOf(a.id))
        r2.unlock()
        assertTrue(pruned.removedBlobs >= 1)
        val after = ByteArrayOutputStream()
        r2.restoreTo(r2.loadSnapshot(b.id).files.first { it.path == "notes.txt" }, after)
        assertArrayEquals(notes2, after.toByteArray())
        assertTrue(r2.check(100).ok)
    }

    @Test
    fun checkDetectsMissingAndCorruptedBlobs() {
        val r = repo()
        r.init("haslo-haslo", 1000)
        val data = Crypto.randomBytes(200_000)
        snapshot(r, 1_700_000_000_000, listOf(FileEntry("x.bin", data.size.toLong(), 1, store(r, data, 50_000))))
        val files = blobFiles()
        files[0].delete()
        val bytes = files[1].readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        files[1].writeBytes(bytes)
        val res = repo().apply { open("haslo-haslo") }.check(100)
        assertFalse(res.ok)
        assertEquals(1, res.missing.size)
        assertEquals(1, res.corrupted.size)
    }

    @Test(expected = Crypto.WrongPasswordException::class)
    fun wrongPasswordFails() {
        repo().init("pierwsze-haslo", 1000)
        repo().open("inne-haslo")
    }

    @Test
    fun passwordChangeKeepsData() {
        val r = repo()
        r.init("stare-haslo", 1000)
        val data = "abc".repeat(1000).toByteArray()
        val s = snapshot(r, 1_700_000_000_000, listOf(FileEntry("a", data.size.toLong(), 1, store(r, data, 1000))))
        r.changePassword("nowe-haslo", 1000)
        val r2 = repo().apply { open("nowe-haslo") }
        val out = ByteArrayOutputStream()
        r2.restoreTo(r2.loadSnapshot(s.id).files[0], out)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test(expected = Repository.RepoLockedException::class)
    fun foreignLockBlocks() {
        val r = repo().apply { init("haslo-123", 1000) }
        r.lock("telefonA", "Telefon A")
        repo().apply { open("haslo-123") }.lock("telefonB", "Telefon B")
    }
}
