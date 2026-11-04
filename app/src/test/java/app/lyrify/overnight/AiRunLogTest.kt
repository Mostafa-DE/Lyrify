package app.lyrify.overnight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class AiRunLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** 2026-11-02 13:05:37 UTC. */
    private var now = 1_793_624_737_000L
    private val file: File get() = File(folder.root, "ai-run.log")

    private fun log(maxBytes: Long = AiRunLog.MAX_BYTES) = AiRunLog(file, clock = { now }, zone = ZoneOffset.UTC, maxBytes = maxBytes)

    @Test
    fun `each event is one line with its time`() {
        val log = log()
        log.append("AI: run started")
        now += 61_000
        log.append("AI: two\nlines")

        assertEquals(listOf("11-02 13:05:37 AI: run started", "11-02 13:06:38 AI: two lines"), file.readLines())
        assertEquals(file.readLines(), log.tail(10))
    }

    @Test
    fun `past half its size the log becomes the old one, and the two keep about the size`() {
        val log = log(maxBytes = 1_000)
        repeat(100) { log.append("event $it") }

        val old = File(file.path + ".1")
        assertTrue(old.length() in 500L..540L)
        assertTrue(file.length() < 540L)
        val tail = log.tail(1_000)
        assertEquals("event 99", tail.last().substringAfter("13:05:37 "))
        // Whole lines, oldest first, across both files.
        val numbers = tail.map { it.substringAfterLast(' ').toInt() }
        assertEquals((numbers.first()..99).toList(), numbers)
        assertTrue(numbers.first() < 80)
    }

    @Test
    fun `the tail is the last lines`() {
        val log = log()
        repeat(5) { log.append("event $it") }

        assertEquals(listOf("event 3", "event 4"), log.tail(2).map { it.substringAfter("13:05:37 ") })
    }

    @Test
    fun `without a log there is no line`() {
        assertEquals(emptyList<String>(), log().tail(10))
    }
}
