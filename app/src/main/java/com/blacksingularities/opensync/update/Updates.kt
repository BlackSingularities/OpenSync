package com.blacksingularities.opensync.update

import org.json.JSONObject
import java.io.File

data class ReleaseInfo(
    val version: String,
    val notes: String,
    val assetUrl: String,
    val sizeBytes: Long,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val info: ReleaseInfo, val progress: Float) : UpdateState
    data class Ready(val info: ReleaseInfo, val file: File) : UpdateState
    data class Installing(val info: ReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Najnowsze wydanie z odpowiedzi GitHub API (plik APK z listy zasobów). */
fun parseRelease(json: String): ReleaseInfo {
    val o = JSONObject(json)
    var url = ""
    var size = 0L
    val assets = o.optJSONArray("assets")
    if (assets != null) {
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                url = a.optString("browser_download_url")
                size = a.optLong("size")
                break
            }
        }
    }
    if (url.isEmpty()) error("Wydanie nie zawiera pliku APK")
    return ReleaseInfo(
        version = o.optString("tag_name").removePrefix("v"),
        notes = o.optString("body").substringBefore("\n---").trim(),
        assetUrl = url,
        sizeBytes = size,
        pageUrl = o.optString("html_url"),
    )
}

/** Porównanie numeryczne wersji „0.1.12” > „0.1.9”; przyrostki typu „-dev” są pomijane. */
fun isNewer(remote: String, local: String): Boolean {
    fun parts(v: String) = v.split('.', '-').mapNotNull { it.toIntOrNull() }
    val r = parts(remote)
    val l = parts(local)
    for (i in 0 until maxOf(r.size, l.size)) {
        val a = r.getOrElse(i) { 0 }
        val b = l.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}
