package com.blacksingularities.opensync.ui

import com.blacksingularities.opensync.data.RunStatus
import com.blacksingularities.opensync.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val PL = Locale.forLanguageTag("pl-PL")

fun formatBytes(b: Long): String {
    if (b < 0) return "?"
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(PL, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

fun formatDateTime(t: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", PL).format(Date(t))
fun formatDate(t: Long): String = SimpleDateFormat("d MMMM yyyy", PL).format(Date(t))
fun formatTime(t: Long): String = SimpleDateFormat("HH:mm", PL).format(Date(t))

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

/** „5 min temu”, „za 3 h”, „wczoraj o 02:30”. */
fun relative(t: Long, now: Long = System.currentTimeMillis()): String {
    val d = t - now
    val a = abs(d)
    val past = d < 0
    fun w(x: String) = if (past) "$x temu" else "za $x"
    return when {
        a < 60_000 -> if (past) "przed chwilą" else "za chwilę"
        a < 3_600_000 -> w("${a / 60_000} min")
        a < 86_400_000 -> w("${a / 3_600_000} h")
        a < 2 * 86_400_000 -> (if (past) "wczoraj" else "jutro") + " o " + formatTime(t)
        a < 7 * 86_400_000L -> w("${a / 86_400_000} dni")
        else -> formatDateTime(t)
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return "$n " + when {
        n == 1 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

fun statusColor(s: RunStatus) = when (s) {
    RunStatus.OK -> Palette.Ok
    RunStatus.WARN -> Palette.Warn
    RunStatus.FAILED -> Palette.Record
    RunStatus.SKIPPED, RunStatus.CANCELLED -> Palette.TextFaint
}
