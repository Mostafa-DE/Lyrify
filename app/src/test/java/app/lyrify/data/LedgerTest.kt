package app.lyrify.data

import app.lyrify.lyrics.Candidate
import app.lyrify.lyrics.LOOKUP_REV
import app.lyrify.lyrics.LocalTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
            LyricsStatus.NEEDS_CHECK,
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
    fun `dropping an entry leaves the other entries and the unread ones as they are`() {
        val unread = mapOf("/c.mp3" to JsonObject(mapOf("status" to JsonPrimitive("FROM_A_NEWER_VERSION"))))
        val ledger = LedgerState(unknownEntries = unread).put(entry("/a.mp3", LyricsStatus.SYNCED, 1)).put(entry("/b.mp3", LyricsStatus.EXISTING, 2))
        val dropped = ledger.drop("/a.mp3")
        assertEquals(setOf("/b.mp3"), dropped.entries.keys)
        assertEquals(ledger.entries["/b.mp3"], dropped.entries["/b.mp3"])
        assertEquals(unread, dropped.unknownEntries)
    }

    @Test
    fun `dropping a path without an entry changes nothing`() {
        val ledger = LedgerState().put(entry("/a.mp3", LyricsStatus.SYNCED, 1))
        assertEquals(ledger, ledger.drop("/nowhere.mp3"))
        assertEquals(LedgerState(), LedgerState().drop("/a.mp3"))
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
    fun `an AI rejection keeps its reason, and a 2_0 entry without one reads as before`() {
        val rejected = recorded(null, track, LyricsStatus.AI_REJECTED, 7, now, source = LyricsSource.AI, agreement = 0.55, aiReason = "LOW_AGREEMENT")
        val ledger = LedgerState().put(rejected).put(recorded(null, track.copy(path = "/b.mp3"), LyricsStatus.AI_REJECTED, null, now, aiReason = "TOO_LONG"))
        val back = LedgerJson.decode(LedgerJson.encode(ledger))
        assertEquals(ledger, back)
        assertEquals("LOW_AGREEMENT", back.entries.getValue(track.path).aiReason)
        assertEquals("TOO_LONG", back.entries.getValue("/b.mp3").aiReason)
        // As Lyrify 2.0 wrote it, before the reason was kept: every field reads as before, the reason is unknown.
        val text = """{"schemaVersion":2,"entries":{"/a.mp3":{"path":"/a.mp3","title":"A","artist":"B","album":null,""" +
            """"durationMs":276000,"status":"AI_REJECTED","lrclibId":7,"attempts":0,"lastAttemptAt":5,"nextAttemptAt":null,""" +
            """"updatedAt":5,"durationMatched":true,"sha256":null,"writtenAt":null,"size":null,"pendingText":null,""" +
            """"source":"AI","agreement":0.62}}}"""
        val old = LedgerJson.decode(text).entries.getValue("/a.mp3")
        assertEquals(
            LedgerEntry(
                path = "/a.mp3", title = "A", artist = "B", durationMs = 276_000, status = LyricsStatus.AI_REJECTED, lrclibId = 7,
                lastAttemptAt = 5, updatedAt = 5, durationMatched = true, source = LyricsSource.AI, agreement = 0.62,
            ),
            old,
        )
        assertNull(old.aiReason)
        // Only a rejection has a reason.
        assertNull(recorded(rejected, track, LyricsStatus.AI_FAILED, 7, now + day, source = LyricsSource.AI).aiReason)
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
        val text = """{"schemaVersion":3,"entries":{"/a.mp3":{"path":"/a.mp3","title":"A","durationMs":1000,""" +
            """"status":"SYNCED","updatedAt":5,"future":true}},"other":1}"""
        val ledger = LedgerJson.decode(text)
        assertEquals(LyricsStatus.SYNCED, ledger.entries["/a.mp3"]?.status)
        assertEquals(LedgerState.SCHEMA_VERSION, ledger.schemaVersion)
    }

    @Test(expected = SerializationException::class)
    fun `text that is not a ledger is rejected`() {
        LedgerJson.decode("not json")
    }

    // Schema 1, as v1.2.1 writes it on the phone (fixture made with that encoder)

    private fun schema1(): String = checkNotNull(javaClass.getResource("/ledger/schema1.json")).readText()

    @Test
    fun `a schema 1 ledger keeps every entry, status and field`() {
        val ledger = LedgerJson.decode(schema1())
        assertEquals(LedgerState.SCHEMA_VERSION, ledger.schemaVersion)
        assertEquals(12, ledger.entries.size)
        val removed = ledger.entries.getValue("/storage/emulated/0/Music/Song K.mp3")
        assertEquals(LyricsStatus.REMOVED, removed.status)
        assertEquals("aaaa000000000000000000000000000000000000000000000000000000000011", removed.sha256)
        assertEquals(111L, removed.lrclibId)
        val plain = ledger.entries.getValue("/storage/emulated/0/Music/Song D.mp3")
        assertEquals(LyricsStatus.PLAIN_ONLY, plain.status)
        assertEquals(true, plain.durationMatched)
        assertEquals(1763592200000L, plain.nextAttemptAt)
        val writing = ledger.entries.getValue("/storage/emulated/0/Music/Song L.mp3")
        assertEquals("[00:01.000]placeholder one\n[00:02.000]placeholder two\n", writing.pendingText)
        assertEquals(45L, writing.size)
        val error = ledger.entries.getValue("/storage/emulated/0/Music/Song I.mp3")
        assertEquals(2, error.attempts)
    }

    @Test
    fun `schema 1 lyrics written, being written or removed came from LRCLIB`() {
        val entries = LedgerJson.decode(schema1()).entries.values
        entries.forEach { entry ->
            val expected = if (entry.status in setOf(LyricsStatus.SYNCED, LyricsStatus.WRITING, LyricsStatus.REMOVED)) LyricsSource.LRCLIB else null
            assertEquals(entry.path, expected, entry.source)
            assertNull(entry.path, entry.agreement)
        }
    }

    @Test
    fun `a migrated schema 1 ledger survives a round trip and is not migrated twice`() {
        val migrated = LedgerJson.decode(schema1())
        val again = LedgerJson.decode(LedgerJson.encode(migrated))
        assertEquals(migrated, again)
    }

    @Test
    fun `a v1 write marker still completes as LRCLIB lyrics`() {
        val writing = LedgerJson.decode(schema1()).entries.getValue("/storage/emulated/0/Music/Song L.mp3")
        assertEquals(LyricsStatus.SYNCED, writing.writtenStatus())
    }

    // AI lyrics

    @Test
    fun `AI lyrics on disk are never looked up again and can be removed`() {
        listOf(LyricsStatus.AI_TRANSCRIBED, LyricsStatus.AI_ALIGNED).forEach {
            val entry = recorded(null, track, it, lrclibId = null, now = now, sha256 = "ab12", source = LyricsSource.AI)
            assertNull(entry.nextAttemptAt)
            assertEquals(LyricsSource.AI, entry.source)
            assertTrue(entry.removable)
            assertTrue(it.written)
        }
    }

    @Test
    fun `AI verdicts are settled for LRCLIB too`() {
        listOf(LyricsStatus.AI_INSTRUMENTAL, LyricsStatus.AI_REJECTED, LyricsStatus.AI_FAILED).forEach {
            val entry = recorded(recorded(null, track, LyricsStatus.PLAIN_ONLY, 7, now - day), track, it, 7, now)
            assertNull(entry.nextAttemptAt)
            assertFalse(entry.isDue(now + 1_000 * day))
            assertFalse(entry.removable)
        }
    }

    @Test
    fun `the AI keeps the agreement and what LRCLIB said`() {
        val plain = recorded(null, track, LyricsStatus.PLAIN_ONLY, 7, now - day, durationMatched = true)
        val rejected = recorded(plain, track, LyricsStatus.AI_REJECTED, plain.lrclibId, now, durationMatched = plain.durationMatched, agreement = 0.42)
        assertEquals(0.42, rejected.agreement ?: 0.0, 0.0)
        assertEquals(7L, rejected.lrclibId)
        assertEquals(true, rejected.durationMatched)
        assertEquals(now, rejected.lastAttemptAt)
    }

    @Test
    fun `AI failures in a row are counted and retried after a day, doubling, at most 30 days`() {
        val first = recorded(recorded(null, track, LyricsStatus.NOT_FOUND, null, now - day), track, LyricsStatus.AI_FAILED, null, now)
        val second = recorded(first, track, LyricsStatus.AI_FAILED, null, now + day)
        assertEquals(1, first.attempts)
        assertEquals(2, second.attempts)
        assertEquals(now + day, first.aiRetryAt)
        assertEquals(now + day + 2 * day, second.aiRetryAt)
        assertEquals(30 * day, Retry.aiDelayMs(9))
        assertNull(recorded(null, track, LyricsStatus.NOT_FOUND, null, now).aiRetryAt)
    }

    @Test
    fun `an AI failure after other statuses starts counting again`() {
        val error = recorded(null, track, LyricsStatus.ERROR, null, now)
        assertEquals(1, recorded(error, track, LyricsStatus.AI_FAILED, null, now + hour).attempts)
    }

    @Test
    fun `only songs without lyrics or an AI verdict wait for the AI`() {
        val waiting = LyricsStatus.entries.filter { it.waitsForAi }
        assertEquals(listOf(LyricsStatus.PLAIN_ONLY, LyricsStatus.NOT_FOUND, LyricsStatus.AI_FAILED), waiting)
        assertEquals(listOf(LyricsStatus.SYNCED, LyricsStatus.AI_TRANSCRIBED, LyricsStatus.AI_ALIGNED), LyricsStatus.entries.filter { it.written })
        assertEquals(LyricsStatus.entries.filter { it.name.startsWith("AI_") }, LyricsStatus.entries.filter { it.byAi })
    }

    @Test
    fun `a write marker completes as what is being written`() {
        val lrclib = recorded(null, track, LyricsStatus.WRITING, 5, now, sha256 = "ab12", source = LyricsSource.LRCLIB)
        val heard = recorded(null, track, LyricsStatus.WRITING, null, now, sha256 = "ab12", source = LyricsSource.AI)
        val timed = recorded(null, track, LyricsStatus.WRITING, 5, now, sha256 = "ab12", source = LyricsSource.AI, agreement = 0.8)
        assertEquals(LyricsStatus.SYNCED, lrclib.writtenStatus())
        assertEquals(LyricsStatus.AI_TRANSCRIBED, heard.writtenStatus())
        assertEquals(LyricsStatus.AI_ALIGNED, timed.writtenStatus())
    }

    @Test
    fun `removed AI lyrics keep their source and hash`() {
        val removed = recorded(null, track, LyricsStatus.AI_ALIGNED, 5, now, sha256 = "ab12", source = LyricsSource.AI, agreement = 0.7).removed(now + hour)
        assertEquals(LyricsStatus.REMOVED, removed.status)
        assertEquals(LyricsSource.AI, removed.source)
        assertEquals("ab12", removed.sha256)
        assertFalse(removed.status.waitsForAi)
    }

    @Test
    fun `AI entries survive a round trip`() {
        val ledger = LedgerState()
            .put(recorded(null, track, LyricsStatus.AI_ALIGNED, 5, now, sha256 = "ab12", size = 10, source = LyricsSource.AI, agreement = 0.75))
            .put(recorded(null, track.copy(path = "/b.mp3"), LyricsStatus.AI_REJECTED, 6, now, agreement = 0.31))
            .put(recorded(null, track.copy(path = "/c.mp3"), LyricsStatus.AI_FAILED, null, now))
            .put(recorded(null, track.copy(path = "/d.mp3"), LyricsStatus.AI_INSTRUMENTAL, null, now))
            .put(recorded(null, track.copy(path = "/e.mp3"), LyricsStatus.AI_TRANSCRIBED, null, now, sha256 = "cd34", source = LyricsSource.AI))
        assertEquals(ledger, LedgerJson.decode(LedgerJson.encode(ledger)))
    }

    // The check list

    private val candidate = Candidate(11, "Song A", "Band A", "Album A", 276.0, synced = true)

    @Test
    fun `a song to check keeps its candidates, and the AI leaves it alone`() {
        val entry = recorded(null, track, LyricsStatus.NEEDS_CHECK, null, now, candidates = listOf(candidate))
        assertEquals(listOf(candidate), entry.candidates)
        assertFalse(entry.status.waitsForAi)
        assertFalse(entry.status.written)
    }

    @Test
    fun `candidates are kept only while the song needs a check`() {
        val checking = recorded(null, track, LyricsStatus.NEEDS_CHECK, null, now, candidates = listOf(candidate))
        assertEquals(emptyList<Candidate>(), recorded(checking, track, LyricsStatus.NOT_FOUND, null, now + day).candidates)
        assertEquals(emptyList<Candidate>(), recorded(null, track, LyricsStatus.SYNCED, 11, now, candidates = listOf(candidate)).candidates)
    }

    @Test
    fun `a song whose candidates the user turned down stays that way`() {
        val declined = recorded(null, track, LyricsStatus.NOT_FOUND, null, now, declined = true)
        assertTrue(declined.declined)
        assertTrue(recorded(declined, track, LyricsStatus.NOT_FOUND, null, now + 30 * day).declined)
        assertTrue(recorded(declined, track, LyricsStatus.AI_FAILED, null, now + 31 * day).declined)
        assertFalse(recorded(null, track, LyricsStatus.NOT_FOUND, null, now).declined)
    }

    @Test
    fun `lookup statuses carry this version's lookup, the others keep the one before`() {
        val old = recorded(null, track, LyricsStatus.NOT_FOUND, null, now).copy(lookupRev = 0)
        val looked = listOf(
            LyricsStatus.NOT_FOUND, LyricsStatus.UNIDENTIFIED, LyricsStatus.NEEDS_CHECK, LyricsStatus.PLAIN_ONLY,
            LyricsStatus.DURATION_MISMATCH, LyricsStatus.INSTRUMENTAL,
        )
        LyricsStatus.entries.forEach {
            assertEquals(it.name, if (it in looked) LOOKUP_REV else 0, recorded(old, track, it, null, now).lookupRev)
        }
        assertEquals(0, recorded(null, track, LyricsStatus.ERROR, null, now).lookupRev)
    }

    @Test
    fun `not found and no artist from an older lookup are looked up again before anything else`() {
        val old = recorded(null, track, LyricsStatus.NOT_FOUND, null, now).copy(lookupRev = LOOKUP_REV - 1)
        assertTrue(old.lookupStale)
        assertTrue(old.copy(status = LyricsStatus.UNIDENTIFIED).lookupStale)
        assertFalse(old.copy(lookupRev = LOOKUP_REV).lookupStale)
        LyricsStatus.entries.filter { it != LyricsStatus.NOT_FOUND && it != LyricsStatus.UNIDENTIFIED }.forEach {
            assertFalse(it.name, old.copy(status = it).lookupStale)
        }
    }

    @Test
    fun `candidates recorded now had their lyrics compared, and those an older version stored read unchanged, not compared yet`() {
        assertTrue(recorded(null, track, LyricsStatus.NEEDS_CHECK, null, now, candidates = listOf(candidate)).candidatesCompared)
        assertFalse(recorded(null, track, LyricsStatus.NOT_FOUND, null, now).candidatesCompared)
        // A song to check as 2.0.0 stored it, with made-up values.
        val text = """{"schemaVersion":2,"entries":{"/m/a.mp3":{"path":"/m/a.mp3","title":"A","artist":null,"album":null,""" +
            """"durationMs":276000,"status":"NEEDS_CHECK","lrclibId":null,"attempts":0,"lastAttemptAt":5,"nextAttemptAt":7,""" +
            """"updatedAt":5,"durationMatched":null,"sha256":null,"writtenAt":null,"size":null,"pendingText":null,"source":null,""" +
            """"agreement":null,"aiReason":null,"candidates":[{"id":11,"title":"Song A","artist":"Band A","album":"Album A",""" +
            """"duration":276.0,"synced":true}],"declined":false,"lookupRev":1}}}"""
        val ledger = LedgerJson.decode(text)
        val entry = ledger.entries.getValue("/m/a.mp3")
        assertEquals(
            LedgerEntry(
                path = "/m/a.mp3", title = "A", durationMs = 276_000, status = LyricsStatus.NEEDS_CHECK, lastAttemptAt = 5,
                nextAttemptAt = 7, updatedAt = 5, candidates = listOf(candidate), lookupRev = 1,
            ),
            entry,
        )
        assertFalse(entry.candidatesCompared)
        assertEquals(null, entry.candidates.single().differingLines)
        assertEquals(ledger, LedgerJson.decode(LedgerJson.encode(ledger)))
    }

    @Test
    fun `a song to check survives a round trip`() {
        val ledger = LedgerState()
            .put(recorded(null, track, LyricsStatus.NEEDS_CHECK, null, now, candidates = listOf(candidate, candidate.copy(id = 12, album = null, synced = false, differingLines = 2))))
            .put(recorded(null, track.copy(path = "/b.mp3"), LyricsStatus.NOT_FOUND, null, now, declined = true))
        val back = LedgerJson.decode(LedgerJson.encode(ledger))
        assertEquals(ledger, back)
        assertEquals(LOOKUP_REV, back.entries.getValue(track.path).lookupRev)
    }

    @Test
    fun `an entry from before the check list reads unchanged, as found by an older lookup`() {
        // The 18 fields of every entry on the phone (Lyrify 2.0 before the check list), with made-up values.
        val text = """{"schemaVersion":2,"entries":{"/m/a.mp3":{"path":"/m/a.mp3","title":"A","artist":"B","album":"C",""" +
            """"durationMs":200000,"status":"NOT_FOUND","lrclibId":null,"attempts":0,"lastAttemptAt":5,"nextAttemptAt":7,""" +
            """"updatedAt":5,"durationMatched":null,"sha256":null,"writtenAt":null,"size":null,"pendingText":null,""" +
            """"source":null,"agreement":null},"/m/b.mp3":{"path":"/m/b.mp3","title":"D","artist":"E","album":null,""" +
            """"durationMs":180000,"status":"SYNCED","lrclibId":9,"attempts":0,"lastAttemptAt":5,"nextAttemptAt":null,""" +
            """"updatedAt":5,"durationMatched":null,"sha256":"ab12","writtenAt":5,"size":40,"pendingText":null,""" +
            """"source":"LRCLIB","agreement":null}}}"""
        val ledger = LedgerJson.decode(text)
        val notFound = ledger.entries.getValue("/m/a.mp3")
        assertEquals(
            LedgerEntry(
                path = "/m/a.mp3", title = "A", artist = "B", album = "C", durationMs = 200_000, status = LyricsStatus.NOT_FOUND,
                lastAttemptAt = 5, nextAttemptAt = 7, updatedAt = 5,
            ),
            notFound,
        )
        assertEquals(emptyList<Candidate>(), notFound.candidates)
        assertFalse(notFound.declined)
        assertEquals(0, notFound.lookupRev)
        assertTrue(notFound.lookupStale)
        assertEquals(
            LedgerEntry(
                path = "/m/b.mp3", title = "D", artist = "E", durationMs = 180_000, status = LyricsStatus.SYNCED, lrclibId = 9,
                lastAttemptAt = 5, updatedAt = 5, sha256 = "ab12", writtenAt = 5, size = 40, source = LyricsSource.LRCLIB,
            ),
            ledger.entries.getValue("/m/b.mp3"),
        )
        assertEquals(ledger, LedgerJson.decode(LedgerJson.encode(ledger)))
    }

    // Entries from a newer version

    /** A plain entry, one with a field from a newer version, one with a newer status, one with a newer source. */
    private val mixed = """{"schemaVersion":2,"future":1,"entries":{""" +
        """"/m/a.mp3":{"path":"/m/a.mp3","title":"A","durationMs":200000,"status":"SYNCED","lrclibId":9,"updatedAt":5,"source":"LRCLIB"},""" +
        """"/m/b.mp3":{"path":"/m/b.mp3","title":"B","durationMs":180000,"status":"NOT_FOUND","updatedAt":6,"lookupRev":1,"future":{"x":[1,2]}},""" +
        """"/m/c.mp3":{"path":"/m/c.mp3","title":"C","durationMs":190000,"status":"FUTURE_STATUS","updatedAt":7,"future":true},""" +
        """"/m/d.mp3":{"path":"/m/d.mp3","title":"D","durationMs":170000,"status":"REMOVED","updatedAt":8,"source":"FUTURE_SOURCE"}}}"""

    @Test
    fun `entries this version cannot read are kept as stored, and the others read as usual`() {
        val ledger = LedgerJson.decode(mixed)

        assertEquals(
            mapOf(
                "/m/a.mp3" to LedgerEntry(
                    path = "/m/a.mp3", title = "A", durationMs = 200_000, status = LyricsStatus.SYNCED, lrclibId = 9, updatedAt = 5,
                    source = LyricsSource.LRCLIB,
                ),
                "/m/b.mp3" to LedgerEntry(path = "/m/b.mp3", title = "B", durationMs = 180_000, status = LyricsStatus.NOT_FOUND, updatedAt = 6, lookupRev = 1),
            ),
            ledger.entries,
        )
        assertEquals(setOf("/m/c.mp3", "/m/d.mp3"), ledger.unknownEntries.keys)
        assertEquals(1, ledger.counts()[LyricsStatus.SYNCED])
        // Written back exactly as read, also once other songs change.
        val changed = ledger.put(recorded(null, track, LyricsStatus.SYNCED, 3, now))
        val back = LedgerJson.decode(LedgerJson.encode(changed))
        assertEquals(changed, back)
        assertEquals(ledger.unknownEntries, back.unknownEntries)
    }

    @Test
    fun `a song this version records again replaces the entry it could not read`() {
        val ledger = LedgerJson.decode(mixed)

        val again = ledger.put(recorded(null, LocalTrack("/m/c.mp3", "C", null, null, 190_000), LyricsStatus.NOT_FOUND, null, now))

        assertEquals(setOf("/m/d.mp3"), again.unknownEntries.keys)
        assertEquals(LyricsStatus.NOT_FOUND, LedgerJson.decode(LedgerJson.encode(again)).entries["/m/c.mp3"]?.status)
    }

    @Test
    fun `only text that is not a ledger at all is rejected`() {
        listOf("[]", "5", "\"ledger\"", """{"entries":[]}""").forEach { text ->
            assertThrows(text, SerializationException::class.java) { LedgerJson.decode(text) }
        }
        assertEquals(LedgerState(), LedgerJson.decode("""{"schemaVersion":2}"""))
        assertEquals(LedgerState(), LedgerJson.decode("""{"entries":{"/x.mp3":5}}""").copy(unknownEntries = emptyMap()))
    }
}
