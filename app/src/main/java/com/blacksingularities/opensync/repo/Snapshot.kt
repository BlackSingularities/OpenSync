package com.blacksingularities.opensync.repo

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class FileEntry(val path: String, val size: Long, val mtime: Long, val chunks: List<String>)

/** Odnośnik do kopii odczytany z samej nazwy pliku – bez pobierania i odszyfrowania manifestu. */
data class SnapshotRef(val id: String, val time: Long, val jobId: String) {
    companion object {
        private fun fmt() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun newId(time: Long, jobId: String, suffix: String) = fmt().format(Date(time)) + "_" + jobId + "_" + suffix

        fun parse(name: String): SnapshotRef? {
            val parts = name.split('_')
            if (parts.size < 3) return null
            val time = runCatching { fmt().parse(parts[0])!!.time }.getOrNull() ?: return null
            return SnapshotRef(name, time, parts[1])
        }
    }
}

data class Snapshot(
    val id: String,
    val time: Long,
    val jobId: String,
    val jobName: String,
    val device: String,
    val deviceId: String,
    val appVersion: String,
    val tags: List<String>,
    val sources: List<String>,
    val files: List<FileEntry>,
    val durationMs: Long = 0,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val ref get() = SnapshotRef(id, time, jobId)

    fun toJson(): String {
        val arr = JSONArray()
        files.forEach { f -> arr.put(JSONArray().put(f.path).put(f.size).put(f.mtime).put(JSONArray(f.chunks))) }
        return JSONObject()
            .put("format", "opensync-snapshot").put("version", 1)
            .put("id", id).put("time", time).put("jobId", jobId).put("jobName", jobName)
            .put("device", device).put("deviceId", deviceId).put("appVersion", appVersion)
            .put("tags", JSONArray(tags)).put("sources", JSONArray(sources))
            .put("durationMs", durationMs)
            .put("files", arr)
            .toString()
    }

    companion object {
        fun fromJson(text: String): Snapshot {
            val o = JSONObject(text)
            val arr = o.getJSONArray("files")
            val files = ArrayList<FileEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                val c = a.getJSONArray(3)
                files += FileEntry(a.getString(0), a.getLong(1), a.getLong(2), (0 until c.length()).map { c.getString(it) })
            }
            fun strings(name: String) = o.optJSONArray(name)?.let { j -> (0 until j.length()).map { j.getString(it) } } ?: emptyList()
            return Snapshot(
                id = o.getString("id"),
                time = o.getLong("time"),
                jobId = o.optString("jobId"),
                jobName = o.optString("jobName"),
                device = o.optString("device"),
                deviceId = o.optString("deviceId"),
                appVersion = o.optString("appVersion"),
                tags = strings("tags"),
                sources = strings("sources"),
                files = files,
                durationMs = o.optLong("durationMs"),
            )
        }
    }
}

/** Różnice między dwiema kopiami (po ścieżce, rozmiarze i fragmentach). */
data class SnapshotDiff(val added: List<FileEntry>, val removed: List<FileEntry>, val changed: List<FileEntry>) {
    companion object {
        fun of(older: Snapshot, newer: Snapshot): SnapshotDiff {
            val old = older.files.associateBy { it.path }
            val new = newer.files.associateBy { it.path }
            return SnapshotDiff(
                added = newer.files.filter { it.path !in old },
                removed = older.files.filter { it.path !in new },
                changed = newer.files.filter { f -> old[f.path]?.let { it.chunks != f.chunks } == true },
            )
        }
    }
}
