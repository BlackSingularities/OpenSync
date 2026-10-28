package com.blacksingularities.opensync.repo

import com.blacksingularities.opensync.backend.Backend
import com.blacksingularities.opensync.backend.NotFoundException
import com.blacksingularities.opensync.crypto.Crypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Repozytorium kopii (układ podobny do restic, w uproszczeniu):
 *
 * ```
 * config                    – parametry KDF i zaszyfrowany klucz główny (jawny JSON)
 * snapshots/<id>            – zaszyfrowane manifesty kopii; nazwa = czas_zadanie_losowe
 * data/<2 znaki>/<id>       – zaszyfrowane fragmenty plików, id = HMAC treści
 * locks/<urządzenie>_<czas> – blokady chroniące przed równoczesnym porządkowaniem
 * pins                      – lista przypiętych kopii (nieusuwanych przez retencję)
 * ```
 *
 * Każda kopia jest pełna (można ją przywrócić samodzielnie), ale niezmienione fragmenty są
 * współdzielone, więc kolejne kopie zajmują tylko tyle, ile się zmieniło.
 */
class Repository(private val backend: Backend, cacheRoot: File) {
    class RepoLockedException(by: String) : Exception("Repozytorium jest zajęte przez: $by. Spróbuj ponownie później.")
    class NotARepositoryException : Exception("W tym miejscu nie ma repozytorium OpenSync")

    data class CheckResult(
        val snapshots: Int,
        val referencedBlobs: Int,
        val missing: List<String>,
        val corrupted: List<String>,
        val verified: Int,
        val unreadableSnapshots: List<String>,
        val orphans: Int,
    ) {
        val ok get() = missing.isEmpty() && corrupted.isEmpty() && unreadableSnapshots.isEmpty()
    }

    data class PruneResult(val removedSnapshots: Int, val removedBlobs: Int)

    lateinit var repoId: String
        private set
    var created: Long = 0
        private set
    private lateinit var master: Crypto.MasterKey
    private lateinit var configJson: JSONObject
    private val cacheBase = cacheRoot
    private var _cacheDir: File? = null
    val cacheDir: File get() = _cacheDir ?: error("Repozytorium nie jest otwarte")

    private val blobs = HashSet<String>()
    private var blobsLoaded = false
    private var lockName: String? = null

    val isOpen get() = ::master.isInitialized

    fun exists(): Boolean = backend.exists("config")

    fun init(password: String, iterations: Int = Crypto.DEFAULT_ITERATIONS) {
        if (exists()) error("Repozytorium już istnieje")
        val salt = Crypto.randomBytes(32)
        val kek = Crypto.deriveKek(password.toCharArray(), salt, iterations)
        val mk = Crypto.newMasterKey()
        val id = Crypto.randomBytes(16).joinToString("") { "%02x".format(it) }
        configJson = JSONObject()
            .put("format", "opensync-repo")
            .put("version", 1)
            .put("id", id)
            .put("created", System.currentTimeMillis())
            .put("kdf", JSONObject().put("name", "pbkdf2-sha256").put("iterations", iterations).put("salt", Crypto.b64(salt)))
            .put("key", Crypto.b64(Crypto.wrapMaster(kek, mk)))
            .put("cipher", "aes-256-gcm")
            .put("mac", "hmac-sha256")
        backend.write("config", configJson.toString(2).toByteArray())
        attach(mk, configJson)
        blobsLoaded = true
    }

    fun open(password: String) {
        val text = try {
            backend.read("config").toString(Charsets.UTF_8)
        } catch (e: NotFoundException) {
            throw NotARepositoryException()
        }
        val cfg = runCatching { JSONObject(text) }.getOrNull()
        if (cfg == null || cfg.optString("format") != "opensync-repo") throw NotARepositoryException()
        if (cfg.optInt("version") > 1) error("Repozytorium utworzone nowszą wersją OpenSync – zaktualizuj aplikację")
        val kdf = cfg.getJSONObject("kdf")
        val kek = Crypto.deriveKek(password.toCharArray(), Crypto.unb64(kdf.getString("salt")), kdf.getInt("iterations"))
        attach(Crypto.unwrapMaster(kek, Crypto.unb64(cfg.getString("key"))), cfg)
    }

    fun openOrInit(password: String): Boolean {
        if (exists()) { open(password); return false }
        init(password); return true
    }

    private fun attach(mk: Crypto.MasterKey, cfg: JSONObject) {
        master = mk
        configJson = cfg
        repoId = cfg.getString("id")
        created = cfg.optLong("created")
        _cacheDir = File(cacheBase, repoId).apply { mkdirs() }
    }

    /** Zmiana hasła: przepakowanie klucza głównego, bez ponownego szyfrowania danych. */
    fun changePassword(newPassword: String, iterations: Int = Crypto.DEFAULT_ITERATIONS) {
        check(isOpen)
        val salt = Crypto.randomBytes(32)
        val kek = Crypto.deriveKek(newPassword.toCharArray(), salt, iterations)
        val cfg = JSONObject(configJson.toString())
            .put("kdf", JSONObject().put("name", "pbkdf2-sha256").put("iterations", iterations).put("salt", Crypto.b64(salt)))
            .put("key", Crypto.b64(Crypto.wrapMaster(kek, master)))
        backend.write("config", cfg.toString(2).toByteArray())
        configJson = cfg
    }

    // ---- Bloki ----------------------------------------------------------------------------

    private fun blobPath(id: String) = "data/${id.take(2)}/$id"

    /** Odświeża listę bloków z serwera (inne urządzenia mogły coś dodać lub usunąć). */
    fun refreshIndex(onProgress: (Float) -> Unit = {}) {
        blobs.clear()
        val dirs = backend.list("data")
        dirs.forEachIndexed { i, d ->
            backend.list("data/$d").forEach { if (it.length == 64) blobs += it }
            onProgress((i + 1f) / dirs.size.coerceAtLeast(1))
        }
        blobsLoaded = true
    }

    fun hasBlob(id: String): Boolean {
        if (!blobsLoaded) refreshIndex()
        return id in blobs
    }

    val blobCount get() = blobs.size

    /** Zapisuje fragment, jeśli go jeszcze nie ma. Zwraca identyfikator i liczbę wysłanych bajtów (0 = deduplikacja). */
    fun putChunk(data: ByteArray, off: Int, len: Int, level: Int): Pair<String, Long> {
        val id = Crypto.blobId(master, data, off, len)
        if (hasBlob(id)) return id to 0L
        val sealed = Crypto.encryptBlob(master, id, data, off, len, level)
        backend.write(blobPath(id), sealed)
        blobs += id
        return id to sealed.size.toLong()
    }

    fun readChunk(id: String): ByteArray = Crypto.decryptBlob(master, id, backend.read(blobPath(id)))

    // ---- Kopie ----------------------------------------------------------------------------

    fun listSnapshots(): List<SnapshotRef> =
        backend.list("snapshots").mapNotNull { SnapshotRef.parse(it) }.sortedByDescending { it.time }

    fun loadSnapshot(id: String): Snapshot {
        val cached = File(cacheDir, "snapshots/$id.json")
        if (cached.exists()) runCatching { return Snapshot.fromJson(cached.readText()) }
        val text = Crypto.decryptDoc(master, "snapshot:$id", backend.read("snapshots/$id"))
        val snap = Snapshot.fromJson(text)
        cached.parentFile?.mkdirs()
        cached.writeText(text)
        return snap
    }

    fun newSnapshotId(time: Long, jobId: String) =
        SnapshotRef.newId(time, jobId.filter { it.isLetterOrDigit() }.ifEmpty { "job" }, Random.nextInt(0x10000).toString(16).padStart(4, '0'))

    fun saveSnapshot(s: Snapshot) {
        val json = s.toJson()
        backend.write("snapshots/${s.id}", Crypto.encryptDoc(master, "snapshot:${s.id}", json))
        File(cacheDir, "snapshots/${s.id}.json").apply { parentFile?.mkdirs() }.writeText(json)
    }

    fun deleteSnapshot(id: String) {
        backend.delete("snapshots/$id")
        File(cacheDir, "snapshots/$id.json").delete()
    }

    fun pins(): Set<String> = runCatching {
        val arr = JSONArray(backend.read("pins").toString(Charsets.UTF_8))
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun setPinned(id: String, pinned: Boolean) {
        val set = pins().toMutableSet()
        if (pinned) set += id else set -= id
        backend.write("pins", JSONArray(set.sorted()).toString().toByteArray())
    }

    // ---- Blokady --------------------------------------------------------------------------

    fun lock(deviceId: String, deviceName: String, staleAfterMs: Long = 3 * 3_600_000L) {
        val now = System.currentTimeMillis()
        for (name in backend.list("locks")) {
            val parts = name.split('_')
            val time = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            when {
                parts[0] == deviceId -> runCatching { backend.delete("locks/$name") }
                now - time > staleAfterMs -> runCatching { backend.delete("locks/$name") }
                else -> {
                    val who = runCatching { backend.read("locks/$name").toString(Charsets.UTF_8) }.getOrDefault("inne urządzenie")
                    throw RepoLockedException(who)
                }
            }
        }
        val name = "${deviceId}_$now"
        backend.write("locks/$name", deviceName.toByteArray())
        lockName = name
    }

    fun unlock() {
        lockName?.let { runCatching { backend.delete("locks/$it") } }
        lockName = null
    }

    // ---- Porządkowanie i weryfikacja ------------------------------------------------------

    /** Usuwa wskazane kopie i fragmenty, do których nie odwołuje się już żadna kopia. */
    fun prune(remove: List<String>, onProgress: (String, Float) -> Unit = { _, _ -> }): PruneResult {
        remove.forEachIndexed { i, id ->
            onProgress("Usuwanie starych kopii", i.toFloat() / remove.size.coerceAtLeast(1))
            deleteSnapshot(id)
        }
        val refs = listSnapshots()
        val used = HashSet<String>()
        refs.forEachIndexed { i, r ->
            onProgress("Analiza kopii", i.toFloat() / refs.size.coerceAtLeast(1))
            // Gdy manifestu nie da się odczytać, nie usuwamy niczego – bezpieczniej zostawić śmieci.
            val s = runCatching { loadSnapshot(r.id) }.getOrElse { return PruneResult(remove.size, 0) }
            s.files.forEach { used.addAll(it.chunks) }
        }
        refreshIndex()
        val orphans = blobs.filter { it !in used }
        orphans.forEachIndexed { i, id ->
            if (i % 20 == 0) onProgress("Zwalnianie miejsca", i.toFloat() / orphans.size.coerceAtLeast(1))
            runCatching { backend.delete(blobPath(id)) }.onSuccess { blobs -= id }
        }
        return PruneResult(remove.size, orphans.size)
    }

    /**
     * Weryfikacja: każdy manifest musi się odszyfrować, każdy fragment musi istnieć,
     * a [samplePct] % fragmentów jest pobierane i sprawdzane kryptograficznie (100 = pełna).
     */
    fun check(samplePct: Int, onProgress: (String, Float) -> Unit = { _, _ -> }): CheckResult {
        val refs = listSnapshots()
        val used = LinkedHashSet<String>()
        val unreadable = ArrayList<String>()
        refs.forEachIndexed { i, r ->
            onProgress("Odczyt kopii", i.toFloat() / refs.size.coerceAtLeast(1))
            File(cacheDir, "snapshots/${r.id}.json").delete()
            runCatching { loadSnapshot(r.id) }
                .onSuccess { s -> s.files.forEach { used.addAll(it.chunks) } }
                .onFailure { unreadable += r.id }
        }
        onProgress("Lista fragmentów", 0f)
        refreshIndex()
        val missing = used.filter { it !in blobs }
        val present = used.filter { it in blobs }
        val sample = when {
            samplePct >= 100 -> present
            samplePct <= 0 -> emptyList()
            else -> present.shuffled().take((present.size * samplePct / 100.0).toInt().coerceAtLeast(1).coerceAtMost(present.size))
        }
        val corrupted = ArrayList<String>()
        sample.forEachIndexed { i, id ->
            if (i % 5 == 0) onProgress("Sprawdzanie fragmentów", i.toFloat() / sample.size.coerceAtLeast(1))
            runCatching { readChunk(id) }.onFailure { corrupted += id }
        }
        return CheckResult(refs.size, used.size, missing, corrupted, sample.size, unreadable, blobs.count { it !in used })
    }

    /** Odczyt pliku z kopii fragment po fragmencie. */
    fun restoreTo(entry: FileEntry, out: java.io.OutputStream, onBytes: (Long) -> Unit = {}) {
        for (id in entry.chunks) {
            val data = readChunk(id)
            out.write(data)
            onBytes(data.size.toLong())
        }
    }

    fun freeSpace() = backend.freeSpace()
}
