package com.blacksingularities.opensync.repo

import com.blacksingularities.opensync.data.Retention
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

/**
 * Wybór kopii do zachowania. Kopia zostaje, jeśli spełnia którąkolwiek regułę:
 * N najnowszych, najnowsza w każdej z ostatnich N godzin/dni/tygodni/miesięcy/lat, młodsza
 * niż N dni albo przypięta. Najnowsza kopia zostaje zawsze.
 */
object RetentionPolicy {
    data class Decision(val keep: List<SnapshotRef>, val remove: List<SnapshotRef>, val reasons: Map<String, List<String>>)

    fun apply(
        snapshots: List<SnapshotRef>,
        r: Retention,
        now: Long,
        pinned: Set<String> = emptySet(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Decision {
        val sorted = snapshots.sortedByDescending { it.time }
        if (!r.enabled || sorted.isEmpty()) return Decision(sorted, emptyList(), emptyMap())
        val reasons = LinkedHashMap<String, MutableList<String>>()
        fun keep(s: SnapshotRef, why: String) { reasons.getOrPut(s.id) { mutableListOf() } += why }

        keep(sorted.first(), "najnowsza")
        sorted.take(r.keepLast).forEach { keep(it, "ostatnie") }
        sorted.filter { it.id in pinned }.forEach { keep(it, "przypięta") }
        if (r.keepWithinDays > 0) {
            val limit = now - r.keepWithinDays * 86_400_000L
            sorted.filter { it.time >= limit }.forEach { keep(it, "z ostatnich ${r.keepWithinDays} dni") }
        }

        fun bucketed(count: Int, label: String, key: (java.time.ZonedDateTime) -> String) {
            if (count <= 0) return
            var last: String? = null
            var n = 0
            for (s in sorted) {
                val k = key(Instant.ofEpochMilli(s.time).atZone(zone))
                if (k != last) {
                    last = k
                    keep(s, label)
                    if (++n >= count) break
                }
            }
        }
        bucketed(r.keepHourly, "godzinowa") { "%d-%03d-%02d".format(it.year, it.dayOfYear, it.hour) }
        bucketed(r.keepDaily, "dzienna") { "%d-%03d".format(it.year, it.dayOfYear) }
        bucketed(r.keepWeekly, "tygodniowa") { "%d-W%02d".format(it.get(IsoFields.WEEK_BASED_YEAR), it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)) }
        bucketed(r.keepMonthly, "miesięczna") { "%d-%02d".format(it.year, it.monthValue) }
        bucketed(r.keepYearly, "roczna") { "%d".format(it.year) }

        val (keep, remove) = sorted.partition { it.id in reasons }
        return Decision(keep, remove, reasons)
    }
}
