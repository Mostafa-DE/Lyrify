package app.lyrify.data

import app.lyrify.lyrics.LocalTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The ledger's DataStore on a real file: what a file it cannot read, wholly or in part, turns into. */
class LedgerStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logs = mutableListOf<String>()

    @After
    fun stop() = scope.cancel()

    private fun ledgerFile(text: String): File = File(folder.root, "ledger.json").apply { writeText(text) }

    /** The app's `files` folder, where copies of a ledger that is not one at all go. */
    private val backupDir: File by lazy { folder.newFolder("files") }

    private fun backup(time: Long): File = File(backupDir, "ledger-corrupt-$time.json")

    private fun store(file: File) = ledgerStore(file, backupDir, scope, clock = { NOW }) { synchronized(logs) { logs += it } }

    private fun backups(): List<String> = backupDir.list().orEmpty().sorted()

    @Test
    fun `a file that is not a ledger is kept aside before the store starts over`() = runBlocking {
        val broken = """{"schemaVersion":2,"entries":{"/m/a.mp3":{"path":"/m/a.mp3","ti"""
        val file = ledgerFile(broken)

        assertEquals(LedgerState(), store(file).data.first())

        assertEquals(listOf("ledger-corrupt-$NOW.json"), backups())
        assertArrayEquals(broken.toByteArray(), backup(NOW).readBytes())
        assertEquals(LedgerState(), LedgerJson.decode(file.readText()))
        // The log names the copy and its size, never what the ledger holds.
        assertTrue(logs.toString(), logs.any { "ledger-corrupt-$NOW.json" in it && "${broken.length} bytes" in it })
        assertFalse(logs.toString(), logs.any { "/m/a.mp3" in it })
    }

    @Test
    fun `only the three newest copies are kept`() = runBlocking {
        listOf(NOW - 3, NOW - 2, NOW - 1).forEach { backup(it).writeText("copy $it") }
        backupDir.resolve("other.json").writeText("not a copy")

        assertEquals(LedgerState(), store(ledgerFile("not a ledger")).data.first())

        assertEquals(listOf("ledger-corrupt-${NOW - 2}.json", "ledger-corrupt-${NOW - 1}.json", "ledger-corrupt-$NOW.json", "other.json"), backups())
    }

    @Test
    fun `entries from a newer version never make the store start over, and are written back as they were`() = runBlocking {
        val newer = """{"path":"/m/b.mp3","title":"B","durationMs":1000,"status":"FUTURE_STATUS","updatedAt":5}"""
        val file = ledgerFile(
            """{"schemaVersion":2,"entries":{"/m/a.mp3":{"path":"/m/a.mp3","title":"A","durationMs":1000,""" +
                """"status":"NOT_FOUND","updatedAt":5,"future":[1]},"/m/b.mp3":$newer}}""",
        )
        val store = store(file)

        assertEquals(setOf("/m/a.mp3"), store.data.first().entries.keys)
        store.updateData { it.put(recorded(null, LocalTrack("/m/c.mp3", "C", null, null, 1_000), LyricsStatus.NOT_FOUND, null, NOW)) }

        val stored = LedgerJson.decode(file.readText())
        assertEquals(setOf("/m/a.mp3", "/m/c.mp3"), stored.entries.keys)
        assertEquals(LedgerJson.decode("""{"entries":{"/m/b.mp3":$newer}}""").unknownEntries, stored.unknownEntries)
        assertEquals(emptyList<String>(), backups())
    }

    @Test
    fun `a copy made while the clock stepped back is never the one removed`() = runBlocking {
        listOf(NOW + 1, NOW + 2, NOW + 3).forEach { backup(it).writeText("copy $it") }

        assertEquals(LedgerState(), store(ledgerFile("not a ledger")).data.first())

        assertEquals(listOf("ledger-corrupt-$NOW.json", "ledger-corrupt-${NOW + 2}.json", "ledger-corrupt-${NOW + 3}.json"), backups())
        assertEquals("not a ledger", backup(NOW).readText())
    }

    @Test
    fun `a file that cannot be kept aside is not replaced, and reads as unreadable rather than empty`() = runBlocking {
        val file = ledgerFile("not a ledger")
        backup(NOW).writeText("an earlier copy")
        val store = store(file)

        try {
            store.data.first()
            fail("the read should fail")
        } catch (_: IOException) {
            // Nothing replaced: the read fails until a copy can be made.
        }
        assertEquals(LedgerState.UNREADABLE, readableLedger(store.data) { synchronized(logs) { logs += it } }.first())
        assertFalse(LedgerState.UNREADABLE.readable)

        assertEquals("not a ledger", file.readText())
        assertEquals("an earlier copy", backup(NOW).readText())
    }

    private companion object {
        const val NOW: Long = 1_800_000_000_000
    }
}
