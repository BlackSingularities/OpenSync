package com.blacksingularities.opensync.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

enum class DestType(val label: String) {
    SMB("SMB (udział NAS / Windows)"),
    SFTP("SFTP (SSH)"),
    WEBDAV("WebDAV (Nextcloud, NAS)"),
    FOLDER("Folder lub dysk USB"),
}

/** Miejsce przechowywania kopii. Hasła i klucze trzymane są zaszyfrowane kluczem z Android Keystore. */
data class Destination(
    val id: String = newId(),
    val name: String = "",
    val type: DestType = DestType.SMB,
    val host: String = "",
    val port: Int = 0,
    val share: String = "",
    val path: String = "",
    val user: String = "",
    val domain: String = "",
    val password: String = "",
    val privateKey: String = "",
    val url: String = "",
    val insecureTls: Boolean = false,
    val hostKey: String = "",
    val folderUri: String = "",
    val wolMac: String = "",
    val wolBroadcast: String = "",
    val wolWaitSec: Int = 90,
) {
    val defaultPort get() = when (type) {
        DestType.SMB -> 445
        DestType.SFTP -> 22
        DestType.WEBDAV -> 0
        DestType.FOLDER -> 0
    }
    val effectivePort get() = if (port > 0) port else defaultPort

    fun describe(): String = when (type) {
        DestType.SMB -> "smb://$host/$share" + path.trim('/').let { if (it.isEmpty()) "" else "/$it" }
        DestType.SFTP -> "sftp://" + (if (user.isNotBlank()) "$user@" else "") + host + (if (port > 0 && port != 22) ":$port" else "") + "/" + path.trim('/')
        DestType.WEBDAV -> url
        DestType.FOLDER -> folderUri.substringAfterLast("%3A").replace("%2F", "/").ifEmpty { "folder" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type.name).put("host", host).put("port", port)
        .put("share", share).put("path", path).put("user", user).put("domain", domain)
        .put("password", password).put("privateKey", privateKey).put("url", url)
        .put("insecureTls", insecureTls).put("hostKey", hostKey).put("folderUri", folderUri)
        .put("wolMac", wolMac).put("wolBroadcast", wolBroadcast).put("wolWaitSec", wolWaitSec)

    companion object {
        fun fromJson(o: JSONObject) = Destination(
            id = o.optString("id").ifEmpty { newId() },
            name = o.optString("name"),
            type = enumOr(o.optString("type"), DestType.SMB),
            host = o.optString("host"),
            port = o.optInt("port"),
            share = o.optString("share"),
            path = o.optString("path"),
            user = o.optString("user"),
            domain = o.optString("domain"),
            password = o.optString("password"),
            privateKey = o.optString("privateKey"),
            url = o.optString("url"),
            insecureTls = o.optBoolean("insecureTls"),
            hostKey = o.optString("hostKey"),
            folderUri = o.optString("folderUri"),
            wolMac = o.optString("wolMac"),
            wolBroadcast = o.optString("wolBroadcast"),
            wolWaitSec = o.optInt("wolWaitSec", 90),
        )
    }
}

enum class SourceType(val label: String, val hint: String) {
    SMS("Wiadomości SMS i MMS", "Format zgodny z importem w OpenSMS, z załącznikami MMS"),
    CALLS("Historia połączeń", "JSON i CSV"),
    CONTACTS("Kontakty", "Plik vCard – import w każdej aplikacji kontaktów"),
    CALENDAR("Kalendarze", "Wydarzenia z kalendarzy w telefonie, pliki .ics"),
    PHOTOS("Zdjęcia", "Aparat, zrzuty ekranu i pozostałe albumy"),
    VIDEOS("Filmy", "Wszystkie filmy z galerii"),
    AUDIO("Muzyka i nagrania", "Pliki audio, nagrania dyktafonu"),
    DOWNLOADS("Pobrane", "Folder Pobrane"),
    FOLDERS("Wybrane foldery", "Dowolne foldery, także eksporty innych aplikacji"),
    APPS("Aplikacje", "Lista zainstalowanych aplikacji, opcjonalnie pliki APK"),
    DEVICE("Informacje o urządzeniu", "Model, system, ustawienia OpenSync (bez haseł)"),
}

data class FolderSpec(val uri: String, val label: String) {
    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("label", label)
    companion object {
        fun fromJson(o: JSONObject) = FolderSpec(o.optString("uri"), o.optString("label"))
    }
}

enum class ScheduleMode(val label: String) {
    MANUAL("Tylko ręcznie"),
    INTERVAL("Co kilka godzin"),
    DAILY("Codziennie"),
    WEEKLY("Co tydzień"),
}

data class Schedule(
    val mode: ScheduleMode = ScheduleMode.DAILY,
    val intervalHours: Int = 6,
    val hour: Int = 2,
    val minute: Int = 30,
    val weekday: Int = 7,
) {
    fun describe(): String = when (mode) {
        ScheduleMode.MANUAL -> "ręcznie"
        ScheduleMode.INTERVAL -> "co $intervalHours h"
        ScheduleMode.DAILY -> "codziennie o %02d:%02d".format(hour, minute)
        ScheduleMode.WEEKLY -> "w każdy " + WEEKDAYS[(weekday - 1).coerceIn(0, 6)].lowercase() + " o %02d:%02d".format(hour, minute)
    }

    fun toJson(): JSONObject = JSONObject().put("mode", mode.name).put("intervalHours", intervalHours)
        .put("hour", hour).put("minute", minute).put("weekday", weekday)

    companion object {
        val WEEKDAYS = listOf("Poniedziałek", "Wtorek", "Środa", "Czwartek", "Piątek", "Sobota", "Niedziela")
        fun fromJson(o: JSONObject?) = if (o == null) Schedule() else Schedule(
            enumOr(o.optString("mode"), ScheduleMode.DAILY),
            o.optInt("intervalHours", 6).coerceIn(1, 168),
            o.optInt("hour", 2).coerceIn(0, 23),
            o.optInt("minute", 30).coerceIn(0, 59),
            o.optInt("weekday", 7).coerceIn(1, 7),
        )
    }
}

data class Conditions(
    val unmeteredOnly: Boolean = true,
    val homeSsids: List<String> = emptyList(),
    val chargingOnly: Boolean = false,
    val batteryNotLow: Boolean = true,
    val idleOnly: Boolean = false,
    val onNewMedia: Boolean = false,
    val retryWhenUnreachable: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("unmeteredOnly", unmeteredOnly).put("homeSsids", JSONArray(homeSsids))
        .put("chargingOnly", chargingOnly).put("batteryNotLow", batteryNotLow).put("idleOnly", idleOnly)
        .put("onNewMedia", onNewMedia).put("retryWhenUnreachable", retryWhenUnreachable)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) Conditions() else Conditions(
            o.optBoolean("unmeteredOnly", true),
            o.optJSONArray("homeSsids").strings(),
            o.optBoolean("chargingOnly"),
            o.optBoolean("batteryNotLow", true),
            o.optBoolean("idleOnly"),
            o.optBoolean("onNewMedia"),
            o.optBoolean("retryWhenUnreachable", true),
        )
    }
}

/**
 * Ile kopii trzymać. Działa jak „dziadek–ojciec–syn”: kopia zostaje, gdy spełnia którąkolwiek
 * regułę (np. 7 ostatnich + po jednej z 4 ostatnich tygodni + po jednej z 12 miesięcy).
 */
data class Retention(
    val enabled: Boolean = true,
    val keepLast: Int = 10,
    val keepHourly: Int = 0,
    val keepDaily: Int = 7,
    val keepWeekly: Int = 4,
    val keepMonthly: Int = 12,
    val keepYearly: Int = 3,
    val keepWithinDays: Int = 0,
) {
    fun describe(): String {
        if (!enabled) return "wszystkie kopie są zachowywane"
        val parts = buildList {
            if (keepLast > 0) add("$keepLast ostatnich")
            if (keepHourly > 0) add("$keepHourly godzinowych")
            if (keepDaily > 0) add("$keepDaily dziennych")
            if (keepWeekly > 0) add("$keepWeekly tygodniowych")
            if (keepMonthly > 0) add("$keepMonthly miesięcznych")
            if (keepYearly > 0) add("$keepYearly rocznych")
            if (keepWithinDays > 0) add("wszystkie z $keepWithinDays dni")
        }
        return if (parts.isEmpty()) "tylko najnowsza kopia" else parts.joinToString(", ")
    }

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("keepLast", keepLast).put("keepHourly", keepHourly)
        .put("keepDaily", keepDaily).put("keepWeekly", keepWeekly).put("keepMonthly", keepMonthly)
        .put("keepYearly", keepYearly).put("keepWithinDays", keepWithinDays)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) Retention() else Retention(
            o.optBoolean("enabled", true),
            o.optInt("keepLast", 10).coerceAtLeast(0),
            o.optInt("keepHourly", 0).coerceAtLeast(0),
            o.optInt("keepDaily", 7).coerceAtLeast(0),
            o.optInt("keepWeekly", 4).coerceAtLeast(0),
            o.optInt("keepMonthly", 12).coerceAtLeast(0),
            o.optInt("keepYearly", 3).coerceAtLeast(0),
            o.optInt("keepWithinDays", 0).coerceAtLeast(0),
        )
    }
}

data class Filters(
    val exclude: List<String> = listOf("*.tmp", "*/.thumbnails/*", "*/.trashed-*", "*/cache/*"),
    val maxFileMb: Int = 0,
    val skipHidden: Boolean = true,
    val mediaMaxAgeDays: Int = 0,
    val includeApks: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("exclude", JSONArray(exclude)).put("maxFileMb", maxFileMb)
        .put("skipHidden", skipHidden).put("mediaMaxAgeDays", mediaMaxAgeDays).put("includeApks", includeApks)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) Filters() else Filters(
            o.optJSONArray("exclude")?.strings() ?: Filters().exclude,
            o.optInt("maxFileMb"),
            o.optBoolean("skipHidden", true),
            o.optInt("mediaMaxAgeDays"),
            o.optBoolean("includeApks"),
        )
    }
}

data class Job(
    val id: String = newId(),
    val name: String = "Kopia telefonu",
    val enabled: Boolean = true,
    val destinationId: String = "",
    val repoPath: String = "OpenSync",
    val repoPassword: String = "",
    val sources: Set<SourceType> = setOf(SourceType.SMS, SourceType.CALLS, SourceType.CONTACTS, SourceType.CALENDAR, SourceType.PHOTOS, SourceType.VIDEOS, SourceType.DEVICE),
    val folders: List<FolderSpec> = emptyList(),
    val schedule: Schedule = Schedule(),
    val conditions: Conditions = Conditions(),
    val retention: Retention = Retention(),
    val filters: Filters = Filters(),
    val compression: Int = 6,
    val chunkMb: Int = 4,
    val verifyEvery: Int = 10,
    val verifySamplePct: Int = 5,
    val limitKbps: Int = 0,
    val notifySuccess: Boolean = false,
    val tags: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("enabled", enabled).put("destinationId", destinationId)
        .put("repoPath", repoPath).put("repoPassword", repoPassword)
        .put("sources", JSONArray(sources.map { it.name }))
        .put("folders", JSONArray(folders.map { it.toJson() }))
        .put("schedule", schedule.toJson()).put("conditions", conditions.toJson())
        .put("retention", retention.toJson()).put("filters", filters.toJson())
        .put("compression", compression).put("chunkMb", chunkMb).put("verifyEvery", verifyEvery)
        .put("verifySamplePct", verifySamplePct).put("limitKbps", limitKbps).put("notifySuccess", notifySuccess)
        .put("tags", JSONArray(tags))

    companion object {
        fun fromJson(o: JSONObject): Job {
            val folders = o.optJSONArray("folders")
            return Job(
                id = o.optString("id").ifEmpty { newId() },
                name = o.optString("name", "Kopia"),
                enabled = o.optBoolean("enabled", true),
                destinationId = o.optString("destinationId"),
                repoPath = o.optString("repoPath", "OpenSync"),
                repoPassword = o.optString("repoPassword"),
                sources = o.optJSONArray("sources").strings().mapNotNull { n -> SourceType.entries.firstOrNull { it.name == n } }.toSet(),
                folders = (0 until (folders?.length() ?: 0)).map { FolderSpec.fromJson(folders!!.getJSONObject(it)) },
                schedule = Schedule.fromJson(o.optJSONObject("schedule")),
                conditions = Conditions.fromJson(o.optJSONObject("conditions")),
                retention = Retention.fromJson(o.optJSONObject("retention")),
                filters = Filters.fromJson(o.optJSONObject("filters")),
                compression = o.optInt("compression", 6).coerceIn(0, 9),
                chunkMb = o.optInt("chunkMb", 4).coerceIn(1, 64),
                verifyEvery = o.optInt("verifyEvery", 10).coerceAtLeast(0),
                verifySamplePct = o.optInt("verifySamplePct", 5).coerceIn(0, 100),
                limitKbps = o.optInt("limitKbps").coerceAtLeast(0),
                notifySuccess = o.optBoolean("notifySuccess"),
                tags = o.optJSONArray("tags").strings(),
            )
        }
    }
}

enum class RunKind(val label: String) { BACKUP("Kopia"), CHECK("Weryfikacja"), PRUNE("Porządkowanie"), RESTORE("Przywracanie") }

enum class RunStatus(val label: String) { OK("Zakończono"), WARN("Z ostrzeżeniami"), FAILED("Błąd"), SKIPPED("Pominięto"), CANCELLED("Przerwano") }

data class RunRecord(
    val id: String = newId(),
    val jobId: String,
    val jobName: String,
    val kind: RunKind,
    val start: Long,
    val end: Long = 0,
    val status: RunStatus = RunStatus.OK,
    val message: String = "",
    val files: Int = 0,
    val changedFiles: Int = 0,
    val bytesTotal: Long = 0,
    val bytesNew: Long = 0,
    val bytesUploaded: Long = 0,
    val snapshotId: String = "",
    val removedSnapshots: Int = 0,
    val warnings: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("jobId", jobId).put("jobName", jobName).put("kind", kind.name)
        .put("start", start).put("end", end).put("status", status.name).put("message", message)
        .put("files", files).put("changedFiles", changedFiles).put("bytesTotal", bytesTotal)
        .put("bytesNew", bytesNew).put("bytesUploaded", bytesUploaded).put("snapshotId", snapshotId)
        .put("removedSnapshots", removedSnapshots).put("warnings", JSONArray(warnings))

    companion object {
        fun fromJson(o: JSONObject) = RunRecord(
            id = o.optString("id"),
            jobId = o.optString("jobId"),
            jobName = o.optString("jobName"),
            kind = enumOr(o.optString("kind"), RunKind.BACKUP),
            start = o.optLong("start"),
            end = o.optLong("end"),
            status = enumOr(o.optString("status"), RunStatus.OK),
            message = o.optString("message"),
            files = o.optInt("files"),
            changedFiles = o.optInt("changedFiles"),
            bytesTotal = o.optLong("bytesTotal"),
            bytesNew = o.optLong("bytesNew"),
            bytesUploaded = o.optLong("bytesUploaded"),
            snapshotId = o.optString("snapshotId"),
            removedSnapshots = o.optInt("removedSnapshots"),
            warnings = o.optJSONArray("warnings").strings(),
        )
    }
}

inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
