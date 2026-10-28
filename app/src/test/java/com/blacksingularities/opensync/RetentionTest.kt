package com.blacksingularities.opensync

import com.blacksingularities.opensync.data.Retention
import com.blacksingularities.opensync.repo.RetentionPolicy
import com.blacksingularities.opensync.repo.SnapshotRef
import com.blacksingularities.opensync.sources.globMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class RetentionTest {
    private val day = 86_400_000L
    private val now = 1_760_000_000_000L

    // Kopia codziennie o tej samej porze przez 400 dni.
    private val daily = (0 until 400).map { SnapshotRef("s$it", now - it * day, "job") }

    private fun keep(r: Retention, pins: Set<String> = emptySet()) =
        RetentionPolicy.apply(daily, r, now, pins, ZoneOffset.UTC).keep.map { it.id }.toSet()

    @Test
    fun keepLastOnly() {
        assertEquals((0 until 5).map { "s$it" }.toSet(), keep(Retention(true, 5, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun grandfatherFatherSon() {
        val k = keep(Retention(true, 0, 0, 7, 4, 12, 0, 0))
        assertTrue((0 until 7).all { "s$it" in k })
        assertTrue(k.size in 8..23)
        assertFalse("s399" in k)
    }

    @Test
    fun newestAndPinnedAlwaysKept() {
        assertEquals(setOf("s0", "s300"), keep(Retention(true, 0, 0, 0, 0, 0, 0, 0), setOf("s300")))
    }

    @Test
    fun disabledKeepsAll() {
        assertEquals(400, keep(Retention(enabled = false)).size)
    }

    @Test
    fun withinDays() {
        assertEquals(31, keep(Retention(true, 0, 0, 0, 0, 0, 0, 30)).size)
    }

    @Test
    fun snapshotIdRoundTrip() {
        val id = SnapshotRef.newId(now, "abc123", "0f0f")
        val ref = SnapshotRef.parse(id)!!
        assertEquals("abc123", ref.jobId)
        assertEquals(now / 1000, ref.time / 1000)
    }

    @Test
    fun globs() {
        assertTrue(globMatches("*.tmp", "folders/a/b.tmp"))
        assertTrue(globMatches("*/.thumbnails/*", "media/DCIM/.thumbnails/1.jpg"))
        assertTrue(globMatches("media/WhatsApp/*", "media/WhatsApp/Media/x.jpg"))
        assertFalse(globMatches("*.tmp", "media/a.jpg"))
    }
}
