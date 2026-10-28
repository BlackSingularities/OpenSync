package com.blacksingularities.opensync.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Konfiguracja aplikacji (zadania, cele, historia) w plikach JSON w pamięci prywatnej. */
class Store private constructor(context: Context) {
    private val dir = File(context.filesDir, "config").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("opensync", Context.MODE_PRIVATE)

    private val _jobs = MutableStateFlow(readList("jobs.json") { Job.fromJson(it) })
    private val _destinations = MutableStateFlow(readList("destinations.json") { Destination.fromJson(it) })
    private val _history = MutableStateFlow(readList("history.json") { RunRecord.fromJson(it) })

    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()
    val destinations: StateFlow<List<Destination>> = _destinations.asStateFlow()
    val history: StateFlow<List<RunRecord>> = _history.asStateFlow()

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: newId().also { prefs.edit().putString("deviceId", it).apply() }

    var deviceName: String
        get() = prefs.getString("deviceName", null) ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        set(v) = prefs.edit().putString("deviceName", v.trim()).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    var notifyFailures: Boolean
        get() = prefs.getBoolean("notifyFailures", true)
        set(v) = prefs.edit().putBoolean("notifyFailures", v).apply()

    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean("autoUpdateCheck", true)
        set(v) = prefs.edit().putBoolean("autoUpdateCheck", v).apply()

    fun job(id: String) = _jobs.value.firstOrNull { it.id == id }
    fun destination(id: String) = _destinations.value.firstOrNull { it.id == id }

    @Synchronized
    fun saveJob(job: Job) {
        val list = _jobs.value.toMutableList()
        val i = list.indexOfFirst { it.id == job.id }
        if (i >= 0) list[i] = job else list += job
        _jobs.value = list
        writeList("jobs.json", list) { it.toJson() }
    }

    @Synchronized
    fun deleteJob(id: String) {
        _jobs.value = _jobs.value.filterNot { it.id == id }
        writeList("jobs.json", _jobs.value) { it.toJson() }
    }

    @Synchronized
    fun saveDestination(d: Destination) {
        val list = _destinations.value.toMutableList()
        val i = list.indexOfFirst { it.id == d.id }
        if (i >= 0) list[i] = d else list += d
        _destinations.value = list
        writeList("destinations.json", list) { it.toJson() }
    }

    @Synchronized
    fun deleteDestination(id: String) {
        _destinations.value = _destinations.value.filterNot { it.id == id }
        writeList("destinations.json", _destinations.value) { it.toJson() }
    }

    @Synchronized
    fun addRun(r: RunRecord) {
        val list = (listOf(r) + _history.value.filterNot { it.id == r.id }).take(300)
        _history.value = list
        writeList("history.json", list) { it.toJson() }
    }

    @Synchronized
    fun clearHistory() {
        _history.value = emptyList()
        writeList("history.json", emptyList<RunRecord>()) { it.toJson() }
    }

    fun lastRun(jobId: String, kind: RunKind = RunKind.BACKUP) =
        _history.value.firstOrNull { it.jobId == jobId && it.kind == kind && it.status != RunStatus.SKIPPED }

    fun lastSuccess(jobId: String) =
        _history.value.firstOrNull { it.jobId == jobId && it.kind == RunKind.BACKUP && (it.status == RunStatus.OK || it.status == RunStatus.WARN) }

    fun backupsSinceCheck(jobId: String): Int {
        var n = 0
        for (r in _history.value) {
            if (r.jobId != jobId) continue
            if (r.kind == RunKind.CHECK && r.status == RunStatus.OK) break
            if (r.kind == RunKind.BACKUP && r.status != RunStatus.SKIPPED) n++
        }
        return n
    }

    /** Eksport ustawień bez haseł – do przeniesienia na nowy telefon. */
    fun exportConfig(): String = JSONObject()
        .put("format", "opensync-config")
        .put("version", 1)
        .put("jobs", JSONArray(_jobs.value.map { it.copy(repoPassword = "").toJson() }))
        .put("destinations", JSONArray(_destinations.value.map { it.copy(password = "", privateKey = "", folderUri = "").toJson() }))
        .toString(2)

    /** Import scala zadania i cele po identyfikatorze; hasła trzeba podać ponownie. */
    fun importConfig(text: String): Pair<Int, Int> {
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (o == null || o.optString("format") != "opensync-config") error("To nie jest plik ustawień OpenSync")
        val d = o.optJSONArray("destinations") ?: JSONArray()
        val j = o.optJSONArray("jobs") ?: JSONArray()
        for (i in 0 until d.length()) {
            val dest = Destination.fromJson(d.getJSONObject(i))
            val old = destination(dest.id)
            saveDestination(if (old != null) dest.copy(password = old.password, privateKey = old.privateKey, folderUri = old.folderUri) else dest)
        }
        for (i in 0 until j.length()) {
            val job = Job.fromJson(j.getJSONObject(i))
            val old = job(job.id)
            saveJob(job.copy(repoPassword = old?.repoPassword ?: "", folders = if (old != null) old.folders else emptyList()))
        }
        return d.length() to j.length()
    }

    private fun <T> readList(name: String, parse: (JSONObject) -> T): List<T> {
        val f = File(dir, name)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { runCatching { parse(arr.getJSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun <T> writeList(name: String, list: List<T>, toJson: (T) -> JSONObject) {
        val f = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(JSONArray(list.map(toJson)).toString())
        if (!tmp.renameTo(f)) {
            f.delete(); tmp.renameTo(f)
        }
    }

    companion object {
        @Volatile private var instance: Store? = null
        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }
    }
}
