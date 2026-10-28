package com.blacksingularities.opensync.sources

import android.Manifest
import android.content.Context
import android.os.Build
import com.blacksingularities.opensync.data.Job
import com.blacksingularities.opensync.data.SourceType
import java.io.File
import java.io.InputStream

/** Pojedynczy plik do skopiowania. [cacheKey] pozwala pominąć odczyt pliku, który się nie zmienił. */
class Item(
    val path: String,
    val size: Long,
    val mtime: Long,
    val cacheKey: String?,
    val compressible: Boolean = isCompressible(path),
    val open: () -> InputStream,
)

class CollectContext(
    val context: Context,
    val job: Job,
    /** Katalog roboczy na pliki generowane (eksport SMS, vCard, ICS…), usuwany po kopii. */
    val workDir: File,
    val warn: (String) -> Unit,
    val add: (Item) -> Unit,
) {
    /** Zapisuje wygenerowany plik w katalogu roboczym i dodaje go do kopii. */
    fun addGenerated(path: String, bytes: ByteArray) {
        val f = File(workDir, path).apply { parentFile?.mkdirs() }
        f.writeBytes(bytes)
        add(Item(path, f.length(), System.currentTimeMillis(), null, true) { f.inputStream() })
    }

    fun addGenerated(path: String, text: String) = addGenerated(path, text.toByteArray(Charsets.UTF_8))
}

interface Source {
    val type: SourceType
    fun permissions(): List<String>
    fun collect(c: CollectContext)
}

object Sources {
    val all: Map<SourceType, Source> = listOf(
        SmsSource, CallLogSource, ContactsSource, CalendarSource,
        MediaSource(SourceType.PHOTOS), MediaSource(SourceType.VIDEOS), MediaSource(SourceType.AUDIO), MediaSource(SourceType.DOWNLOADS),
        FolderSource, AppsSource, DeviceSource,
    ).associateBy { it.type }

    fun permissionsFor(types: Collection<SourceType>): List<String> = types.flatMap { all[it]?.permissions().orEmpty() }.distinct()

    fun mediaPermission(type: SourceType): List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> when (type) {
            SourceType.PHOTOS -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
            SourceType.VIDEOS -> listOf(Manifest.permission.READ_MEDIA_VIDEO)
            SourceType.AUDIO -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
            else -> emptyList()
        }
        type == SourceType.DOWNLOADS -> emptyList()
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private val INCOMPRESSIBLE = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "dng",
    "mp4", "m4v", "mkv", "mov", "webm", "3gp", "avi",
    "mp3", "m4a", "aac", "ogg", "opus", "flac", "amr",
    "zip", "gz", "tgz", "xz", "bz2", "7z", "rar", "zst", "apk", "aab", "jar", "pdf", "docx", "xlsx", "pptx", "odt", "epub",
)

fun isCompressible(path: String) = path.substringAfterLast('.', "").lowercase() !in INCOMPRESSIBLE

/** Bezpieczna nazwa segmentu ścieżki w repozytorium. */
fun safeName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').ifEmpty { "_" }

/** Dopasowanie wzorca typu glob („*”, „?”) do ścieżki, bez rozróżniania wielkości liter. */
fun globMatches(pattern: String, path: String): Boolean {
    val p = pattern.trim()
    if (p.isEmpty()) return false
    val sb = StringBuilder("^")
    for (ch in p) {
        when (ch) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(ch.toString()))
        }
    }
    sb.append('$')
    val re = Regex(sb.toString(), RegexOption.IGNORE_CASE)
    return re.matches(path) || re.matches("/$path") || (!p.contains('/') && re.matches(path.substringAfterLast('/')))
}
