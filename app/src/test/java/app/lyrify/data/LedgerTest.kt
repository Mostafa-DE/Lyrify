package app.lyrify.data

import app.lyrify.lyrics.LocalTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class LedgerTest {
    private val track = LocalTrack("/music/Nemo.mp3", "Nemo", "Nightwish", "Once", 276_000)
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // Session retries

    @Test
    fun `session retries wait 2, 4 and 8 seconds at the middle of the jitter`() {
        assertEquals(2_000L, Retry.sessionDelayMs(retry = 1, jitter = 0.5))
        assertEquals(4_000L, Retry.sessionDelayMs(retry = 2, jitter = 0.5))
        assertEquals(8_000L, Retry.sessionDelayMs(retry = 3, jitter = 0.5))
    }

    @Test
    fun `jitter moves a session retry by at most a fifth`() {
        assertEquals(1_600L, Retry.sessionDelayMs(retry = 1, jitter = 0.0))
        assertEquals(2_399L, Retry.sessionDelayMs(retry = 1, jitter = 0.99975))
        assertEquals(6_400L, Retry.sessionDelayMs(retry = 3, jitter = 0.0))
    }

    @Test
    fun `four attempts per session`() {
        assertEquals(4, Retry.SESSION_ATTEMPTS)
    }

    // Lookups that failed

    @Test
    fun `failed lookups back off from 30 minutes, doubling, up to a day`() {
        assertEquals(30 * minute, Retry.errorDelayMs(1))
        assertEquals(60 * minute, Retry.errorDelayMs(2))
        assertEquals(2 * hour, Retry.errorDelayMs(3))
        assertEquals(16 * hour, Retry.errorDelayMs(6))
        assertEquals(day, Retry.errorDelayMs(7))
        assertEquals(day, Retry.errorDelayMs(40))
    }

    @Test
    fun `a first error is retried after 30 minutes`() {
        val entry = recorded(null, track, LyricsStatus.ERROR, lrclibId = null, now = now)
        assertEquals(1, entry.attempts)
        assertEquals(now, entry.lastAttemptAt)
        assertEquals(now + 30 * minute, entry.nextAttemptAt)
        assertFalse(entry.isDue(now + 30 * minute - 1))
        assertTrue(entry.isDue(now + 30 * minute))
    }

    @Test
    fun `errors in a row double the wait`() {
        val first = recorded(null, track, LyricsStatus.ERROR, null, now)
        val second = recorded(first, track, LyricsStatus.ERROR, null, now + hour)
        val third = recorded(second, track, LyricsStatus.ERROR, null, now + 3 * hour)
        assertEquals(2, second.attempts)
        assertEquals(now + hour + 60 * minute, second.nextAttemptAt)
        assertEquals(3, third.attempts)
        assertEquals(now + 3 * hour + 2 * hour, third.nextAttemptAt)
    }

    @Test
    fun `an answer from LRCLIB resets the error count`() {
        val error = recorded(null, track, LyricsStatus.ERROR, null, now)
        val found = recorded(error, track, LyricsStatus.NOT_FOUND, null, now + hour)
        assertEquals(0, found.attempts)
        val againError = recorded(found, track, LyricsStatus.ERROR, null, now + 2 * hour)
        assertEquals(1, againError.attempts)
    }

    @Test
    fun `not found, plain only, mismatch, instrumental and no artist are checked again after 30 days`() {
        listOf(
            LyricsStatus.NOT_FOUND,
            LyricsStatus.PLAIN_ONLY,
            LyricsStatus.DURATION_MISMATCH,
            LyricsStatus.INSTRUMENTAL,
            LyricsStatus.UNIDENTIFIED,
        ).forEach {
            val entry = recorded(null, track, it, lrclibId = 9, now = now)
            assertEquals(now + 30 * day, entry.nextAttemptAt)
            assertFalse(entry.isDue(now + 29 * day))
            assertTrue(entry.isDue(now + 30 * day))
        }
    }

    @Test
    fun `written and existing lyrics are never looked up again`() {
        listOf(LyricsStatus.SYNCED, LyricsStatus.EXISTING).forEach {
            val entry = recorded(null, track, it, lrclibId = null, now = now)
            assertNull(entry.nextAttemptAt)
            assertFalse(entry.isDue(now + 1_000 * day))
        }
    }

    @Test
    fun `written lyrics keep the hash of their bytes and when they were written`() {
        val entry = recorded(null, track, LyricsStatus.SYNCED, 5, now, sha256 = "ab12")
        assertEquals("ab12", entry.sha256)
        assertEquals(now, entry.writtenAt)
        assertTrue(entry.removable)
    }

    @Test
    fun `a write marker is never due and keeps the hash, size, text and start of the write`() {
        val marker = recorded(null, track, LyricsStatus.WRITING, 5, now, sha256 = "ab12", size = 300, pendingText = "[00:01.000]a\n")
        assertNull(marker.nextAttemptAt)
        assertEquals("ab12", marker.sha256)
        assertEquals(300L, marker.size)
        assertEquals("[00:01.000]a\n", marker.pendingText)
        assertEquals(now, marker.writtenAt)
        assertEquals(5L, marker.lrclibId)
        assertFalse(marker.removable)
    }

    @Test
    fun `the text being written is kept only while writing`() {
        LyricsStatus.entries.filter { it != LyricsStatus.WRITING }.forEach {
            assertNull(recorded(null, track, it, 5, now, sha256 = "ab12", pendingText = "[00:01.000]a\n").pendingText)
        }
    }

    @Test
    fun `only lyrics Lyrify wrote and hashed can be removed`() {
        assertFalse(recorded(null, track, LyricsStatus.SYNCED, 5, now).removable)
        assertFalse(recorded(null, track, LyricsStatus.EXISTING, null, now).removable)
        assertFalse(recorded(null, track, LyricsStatus.SYNCED, 5, now, sha256 = "ab12").removed(now + hour).removable)
    }

    @Test
    fun `removed lyrics are never looked up again and keep their LRCLIB id`() {
        val removed = recorded(null, track, LyricsStatus.SYNCED, 5, now, sha256 = "ab12").removed(now + hour)
        assertEquals(LyricsStatus.REMOVED, removed.status)
        assertEquals(5L, removed.lrclibId)
        assertEquals(now + hour, removed.updatedAt)
        assertNull(removed.nextAttemptAt)
        assertFalse(removed.isDue(now + 1_000 * day))
        assertNull(recorded(null, track, LyricsStatus.REMOVED, 5, now).nextAttemptAt)
    }

    @Test
    fun `plain lyrics remember whether their duration matched`() {
        assertEquals(false, recorded(null, track, LyricsStatus.PLAIN_ONLY, 7, now, durationMatched = false).durationMatched)
        assertEquals(true, recorded(null, track, LyricsStatus.PLAIN_ONLY, 7, now, durationMatched = true).durationMatched)
        assertNull(recorded(null, track, LyricsStatus.NOT_FOUND, null, now).durationMatched)
    }

    @Test
    fun `plain only keeps the LRCLIB id for v2`() {
        assertEquals(1234L, recorded(null, track, LyricsStatus.PLAIN_ONLY, 1234, now).lrclibId)
    }

    @Test
    fun `an error keeps the id found earlier`() {
        val plain = recorded(null, track, LyricsStatus.PLAIN_ONLY, 1234, now)
        assertEquals(1234L, recorded(plain, track, LyricsStatus.ERROR, null, now + day).lrclibId)
    }

    @Test
    fun `an existing sidecar does not count as a lookup`() {
        val error = recorded(null, track, LyricsStatus.ERROR, null, now)
        val existing = recorded(error, track, LyricsStatus.EXISTING, null, now + hour)
        assertEquals(now, existing.lastAttemptAt)
        assertEquals(now + hour, existing.updatedAt)
    }

    @Test
    fun `an entry copies the track's tags`() {
        val entry = recorded(null, track, LyricsStatus.SYNCED, 5, now)
        assertEquals(LedgerEntry("/music/Nemo.mp3", "Nemo", "Nightwish", "Once", 276_000, LyricsStatus.SYNCED, 5, 0, now, null, now), entry)
        assertEquals(track, entry.track)
    }

    // The ledger as a whole

    private fun entry(path: String, status: LyricsStatus, updatedAt: Long) =
        recorded(null, track.copy(path = path), status, null, updatedAt)

    @Test
    fun `counts per status cover every status`() {
        val ledger = LedgerState()
            .put(entry("/a.mp3", LyricsStatus.SYNCED, 1))
            .put(entry("/b.mp3", LyricsStatus.SYNCED, 2))
            .put(entry("/c.mp3", LyricsStatus.ERROR, 3))
        val counts = ledger.counts()
        assertEquals(LyricsStatus.entries.toSet(), counts.keys)
        assertEquals(2, counts[LyricsStatus.SYNCED])
        assertEquals(1, counts[LyricsStatus.ERROR])
        assertEquals(0, counts[LyricsStatus.NOT_FOUND])
    }

    @Test
    fun `recent is newest first and limited`() {
        val ledger = LedgerState()
            .put(entry("/a.mp3", LyricsStatus.SYNCED, 1))
            .put(entry("/b.mp3", LyricsStatus.NOT_FOUND, 3))
            .put(entry("/c.mp3", LyricsStatus.ERROR, 2))
        assertEquals(listOf("/b.mp3", "/c.mp3"), ledger.recent(limit = 2).map { it.path })
    }

    @Test
    fun `putting an entry replaces the one for the same path`() {
        val ledger = LedgerState()
            .put(entry("/a.mp3", LyricsStatus.ERROR, 1))
            .put(entry("/a.mp3", LyricsStatus.SYNCED, 2))
        assertEquals(1, ledger.entries.size)
        assertEquals(LyricsStatus.SYNCED, ledger.entries["/a.mp3"]?.status)
    }

    @Test
    fun `failed entries are listed for a retry`() {
        val ledger = LedgerState()
            .put(entry("/a.mp3", LyricsStatus.ERROR, 1))
            .put(entry("/b.mp3", LyricsStatus.NOT_FOUND, 2))
            .put(entry("/c.mp3", LyricsStatus.ERROR, 3))
        assertEquals(listOf("/c.mp3", "/a.mp3"), ledger.failed().map { it.path })
    }

    @Test
    fun `errors due again are the ones to retry`() {
        val ledger = LedgerState()
            .put(entry("/due.mp3", LyricsStatus.ERROR, now - hour))
            .put(entry("/later.mp3", LyricsStatus.ERROR, now))
            .put(entry("/found.mp3", LyricsStatus.NOT_FOUND, now - 40 * day))
        assertEquals(listOf("/due.mp3"), ledger.retryDue(now).map { it.path })
    }

    // Writing the ledger

    @Test
    fun `a ledger write that keeps failing is tried 3 more times, 100, 200 and 400 ms apart, then reported`() = runTest {
        val tries = mutableListOf<Long>()
        val failures = mutableListOf<IOException>()

        val written = writeWithRetries(onFailure = { failures += it }) {
            tries += testScheduler.currentTime
            throw IOException("No space left on device")
        }

        assertFalse(written)
        assertEquals(listOf(0L, 100L, 300L, 700L), tries)
        assertEquals(4, failures.size)
    }

    @Test
    fun `a ledger write that works on a retry counts as written`() = runTest {
        var failures = 2
        assertTrue(writeWithRetries(onFailure = {}) { if (failures-- > 0) throw IOException("busy") })
        assertEquals(300L, testScheduler.currentTime)
    }

    // Stored form

    @Test
    fun `the ledger survives a round trip`() {
        val ledger = LedgerState()
            .put(recorded(null, track, LyricsStatus.PLAIN_ONLY, 77, now))
            .put(recorded(null, track.copy(path = "/b.mp3", artist = null, album = null), LyricsStatus.ERROR, null, now))
            .put(recorded(null, track.copy(path = "/c.mp3"), LyricsStatus.SYNCED, 3, now, sha256 = "ab12").removed(now + day))
            .put(recorded(null, track.copy(path = "/d.mp3"), LyricsStatus.PLAIN_ONLY, 4, now, durationMatched = false))
            .put(recorded(null, track.copy(path = "/e.mp3"), LyricsStatus.WRITING, 6, now, sha256 = "cd34", size = 120, pendingText = "[00:01.000]a\n"))
        assertEquals(ledger, LedgerJson.decode(LedgerJson.encode(ledger)))
    }

    @Test
    fun `a ledger written by v1 reads without hashes`() {
        val text = """{"schemaVersion":1,"entries":{"/a.mp3":{"path":"/a.mp3","title":"A","durationMs":1000,""" +
            """"status":"SYNCED","lrclibId":9,"attempts":0,"lastAttemptAt":5,"nextAttemptAt":null,"updatedAt":5}}}"""
        val entry = LedgerJson.decode(text).entries["/a.mp3"]
        assertEquals(LyricsStatus.SYNCED, entry?.status)
        assertNull(entry?.sha256)
        assertNull(entry?.writtenAt)
    }

    @Test
    fun `fields from a newer version are ignored`() {
        val text = """{"schemaVersion":2,"entries":{"/a.mp3":{"path":"/a.mp3","title":"A","durationMs":1000,""" +
            """"status":"SYNCED","updatedAt":5,"future":true}},"other":1}"""
        val ledger = LedgerJson.decode(text)
        assertEquals(LyricsStatus.SYNCED, ledger.entries["/a.mp3"]?.status)
        assertEquals(LedgerState.SCHEMA_VERSION, ledger.schemaVersion)
    }

    @Test(expected = SerializationException::class)
    fun `text that is not a ledger is rejected`() {
        LedgerJson.decode("not json")
    }
}
