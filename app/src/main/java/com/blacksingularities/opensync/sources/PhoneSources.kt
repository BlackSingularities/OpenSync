package com.blacksingularities.opensync.sources

import android.Manifest
import android.content.ContentUris
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.provider.MediaStore.MediaColumns as M
import com.blacksingularities.opensync.data.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SMS w formacie „opensms-backup” (import w OpenSMS bez duplikatów) oraz MMS z załącznikami.
 */
object SmsSource : Source {
    override val type = SourceType.SMS
    override fun permissions() = listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)

    override fun collect(c: CollectContext) {
        val cr = c.context.contentResolver
        val sms = JSONArray()
        cr.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.READ),
            null, null, Telephony.Sms.DATE,
        )?.use { cur ->
            while (cur.moveToNext()) {
                sms.put(
                    JSONObject()
                        .put("address", cur.getString(0) ?: "")
                        .put("body", cur.getString(1) ?: "")
                        .put("date", cur.getLong(2))
                        .put("date_sent", cur.getLong(3))
                        .put("type", cur.getInt(4))
                        .put("read", cur.getInt(5) == 1),
                )
            }
        } ?: c.warn("Brak dostępu do SMS")
        c.addGenerated(
            "messages/sms.json",
            JSONObject().put("format", "opensms-backup").put("version", 1)
                .put("exported", System.currentTimeMillis()).put("sms", sms).toString(),
        )

        val mms = JSONArray()
        runCatching {
            cr.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.SUBJECT, Telephony.Mms.READ, Telephony.Mms.THREAD_ID),
                null, null, Telephony.Mms.DATE,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val msg = JSONObject()
                        .put("id", id)
                        .put("date", cur.getLong(1) * 1000)
                        .put("box", cur.getInt(2))
                        .put("subject", cur.getString(3) ?: "")
                        .put("read", cur.getInt(4) == 1)
                        .put("thread", cur.getLong(5))
                    val addrs = JSONArray()
                    cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)?.use { a ->
                        while (a.moveToNext()) {
                            val addr = a.getString(0) ?: continue
                            if (addr == "insert-address-token") continue
                            addrs.put(JSONObject().put("address", addr).put("type", if (a.getInt(1) == 137) "from" else "to"))
                        }
                    }
                    msg.put("addresses", addrs)
                    val parts = JSONArray()
                    cr.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text", "name", "cl"), "mid=?", arrayOf(id.toString()), null)?.use { p ->
                        while (p.moveToNext()) {
                            val partId = p.getLong(0)
                            val ct = p.getString(1) ?: ""
                            if (ct == "application/smil") continue
                            val part = JSONObject().put("type", ct)
                            if (ct.startsWith("text/")) {
                                part.put("text", p.getString(2) ?: "")
                            } else {
                                val name = safeName(p.getString(3) ?: p.getString(4) ?: "part$partId")
                                val path = "messages/mms/${id}_${partId}_$name"
                                part.put("file", path)
                                val uri = Uri.parse("content://mms/part/$partId")
                                c.add(Item(path, -1, msg.getLong("date"), "mms:$partId") { cr.openInputStream(uri) ?: error("Brak załącznika MMS") })
                            }
                            parts.put(part)
                        }
                    }
                    msg.put("parts", parts)
                    mms.put(msg)
                }
            }
        }.onFailure { c.warn("MMS: ${it.message}") }
        c.addGenerated("messages/mms.json", JSONObject().put("format", "opensync-mms").put("version", 1).put("mms", mms).toString())
    }
}

object CallLogSource : Source {
    override val type = SourceType.CALLS
    override fun permissions() = listOf(Manifest.permission.READ_CALL_LOG)

    override fun collect(c: CollectContext) {
        val arr = JSONArray()
        val csv = StringBuilder("data;numer;nazwa;typ;czas_s\n")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        c.context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
            null, null, CallLog.Calls.DATE,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val type = when (cur.getInt(4)) {
                    CallLog.Calls.INCOMING_TYPE -> "przychodzące"
                    CallLog.Calls.OUTGOING_TYPE -> "wychodzące"
                    CallLog.Calls.MISSED_TYPE -> "nieodebrane"
                    CallLog.Calls.REJECTED_TYPE -> "odrzucone"
                    CallLog.Calls.BLOCKED_TYPE -> "zablokowane"
                    CallLog.Calls.VOICEMAIL_TYPE -> "poczta głosowa"
                    else -> "inne"
                }
                val number = cur.getString(0) ?: ""
                val name = cur.getString(1) ?: ""
                arr.put(
                    JSONObject().put("number", number).put("name", name).put("date", cur.getLong(2))
                        .put("duration", cur.getLong(3)).put("type", cur.getInt(4)),
                )
                csv.append(fmt.format(Date(cur.getLong(2)))).append(';').append(number).append(';')
                    .append(name.replace(';', ',')).append(';').append(type).append(';').append(cur.getLong(3)).append('\n')
            }
        } ?: c.warn("Brak dostępu do historii połączeń")
        c.addGenerated("calls/calls.json", JSONObject().put("format", "opensync-calls").put("version", 1).put("calls", arr).toString())
        c.addGenerated("calls/calls.csv", csv.toString())
    }
}

object ContactsSource : Source {
    override val type = SourceType.CONTACTS
    override fun permissions() = listOf(Manifest.permission.READ_CONTACTS)

    override fun collect(c: CollectContext) {
        val cr = c.context.contentResolver
        val keys = ArrayList<String>()
        cr.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use { cur ->
            while (cur.moveToNext()) cur.getString(0)?.let { keys += it }
        } ?: run { c.warn("Brak dostępu do kontaktów"); return }
        val out = StringBuilder()
        var failed = 0
        for (batch in keys.chunked(50)) {
            val ok = runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI, Uri.encode(batch.joinToString(":")))
                cr.openInputStream(uri)?.use { out.append(it.readBytes().toString(Charsets.UTF_8)) } ?: error("pusto")
            }.isSuccess
            if (!ok) {
                for (key in batch) {
                    runCatching {
                        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, key)
                        cr.openInputStream(uri)?.use { out.append(it.readBytes().toString(Charsets.UTF_8)) }
                    }.onFailure { failed++ }
                }
            }
        }
        if (failed > 0) c.warn("Nie udało się wyeksportować $failed kontaktów")
        c.addGenerated("contacts/contacts.vcf", out.toString())
    }
}

object CalendarSource : Source {
    override val type = SourceType.CALENDAR
    override fun permissions() = listOf(Manifest.permission.READ_CALENDAR)

    override fun collect(c: CollectContext) {
        val cr = c.context.contentResolver
        data class Cal(val id: Long, val name: String, val account: String)
        val cals = ArrayList<Cal>()
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
            null, null, null,
        )?.use { cur -> while (cur.moveToNext()) cals += Cal(cur.getLong(0), cur.getString(1) ?: "Kalendarz", cur.getString(2) ?: "") }
            ?: run { c.warn("Brak dostępu do kalendarza"); return }

        val utc = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val usedNames = HashSet<String>()
        for (cal in cals) {
            val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//OpenSync//PL\r\n")
            sb.append("X-WR-CALNAME:").append(ics(cal.name)).append("\r\n")
            var n = 0
            cr.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY, CalendarContract.Events.RRULE, CalendarContract.Events.DURATION,
                    CalendarContract.Events.UID_2445,
                ),
                "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DELETED}=0", arrayOf(cal.id.toString()), null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    n++
                    val allDay = cur.getInt(6) == 1
                    val start = cur.getLong(4)
                    sb.append("BEGIN:VEVENT\r\n")
                    sb.append("UID:").append(cur.getString(9) ?: "opensync-${cal.id}-${cur.getLong(0)}").append("\r\n")
                    sb.append("DTSTAMP:").append(utc.format(Date())).append("\r\n")
                    if (allDay) sb.append("DTSTART;VALUE=DATE:").append(day.format(Date(start))).append("\r\n")
                    else sb.append("DTSTART:").append(utc.format(Date(start))).append("\r\n")
                    if (!cur.isNull(5) && cur.getLong(5) > 0) {
                        if (allDay) sb.append("DTEND;VALUE=DATE:").append(day.format(Date(cur.getLong(5)))).append("\r\n")
                        else sb.append("DTEND:").append(utc.format(Date(cur.getLong(5)))).append("\r\n")
                    } else if (!cur.getString(8).isNullOrBlank()) {
                        sb.append("DURATION:").append(cur.getString(8)).append("\r\n")
                    }
                    cur.getString(7)?.takeIf { it.isNotBlank() }?.let { sb.append("RRULE:").append(it).append("\r\n") }
                    sb.append("SUMMARY:").append(ics(cur.getString(1) ?: "")).append("\r\n")
                    cur.getString(2)?.takeIf { it.isNotBlank() }?.let { sb.append("DESCRIPTION:").append(ics(it)).append("\r\n") }
                    cur.getString(3)?.takeIf { it.isNotBlank() }?.let { sb.append("LOCATION:").append(ics(it)).append("\r\n") }
                    sb.append("END:VEVENT\r\n")
                }
            }
            sb.append("END:VCALENDAR\r\n")
            if (n == 0) continue
            var name = safeName(listOf(cal.name, cal.account).filter { it.isNotBlank() }.distinct().joinToString(" - "))
            if (!usedNames.add(name)) name += "-${cal.id}"
            c.addGenerated("calendar/$name.ics", sb.toString())
        }
    }

    private fun ics(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")
}

/** Zdjęcia, filmy, audio i pobrane z MediaStore (pamięć wewnętrzna i karty SD). */
class MediaSource(override val type: SourceType) : Source {
    override fun permissions() = Sources.mediaPermission(type)

    override fun collect(c: CollectContext) {
        val (uri, prefix) = when (type) {
            SourceType.PHOTOS -> android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL) to "media"
            SourceType.VIDEOS -> android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL) to "media"
            SourceType.AUDIO -> android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL) to "media"
            else -> android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL) to "media"
        }
        val minDate = if (c.job.filters.mediaMaxAgeDays > 0) (System.currentTimeMillis() / 1000) - c.job.filters.mediaMaxAgeDays * 86_400L else 0L
        val selection = buildString {
            append("${M.SIZE} > 0")
            if (minDate > 0) append(" AND ${M.DATE_MODIFIED} >= $minDate")
            append(" AND ${M.IS_PENDING} = 0")
        }
        val seen = HashSet<String>()
        val cr = c.context.contentResolver
        cr.query(uri, arrayOf(M._ID, M.RELATIVE_PATH, M.DISPLAY_NAME, M.SIZE, M.DATE_MODIFIED, M.VOLUME_NAME), selection, null, M.DATE_MODIFIED)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val rel = (cur.getString(1) ?: "").trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { safeName(it) }
                val name = safeName(cur.getString(2) ?: "plik_$id")
                val volume = cur.getString(5) ?: "external_primary"
                val root = if (volume == "external_primary") prefix else "$prefix/sd-${safeName(volume)}"
                var path = listOf(root, rel, name).filter { it.isNotEmpty() }.joinToString("/")
                if (!seen.add(path.lowercase())) {
                    path = path.substringBeforeLast('.', path) + "_$id" + (if (name.contains('.')) "." + name.substringAfterLast('.') else "")
                    seen += path.lowercase()
                }
                val itemUri = ContentUris.withAppendedId(uri, id)
                val size = cur.getLong(3)
                val mtime = cur.getLong(4) * 1000
                c.add(Item(path, size, mtime, "ms:$volume:$id") { cr.openInputStream(itemUri) ?: error("Plik niedostępny") })
            }
        } ?: c.warn("${type.label}: brak dostępu")
    }
}
