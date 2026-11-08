package app.lyrify.ui

import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.Removal
import app.lyrify.media.PlayingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val PATH = "/music/Paper Lanterns.mp3"

class NowScreenTest {
    // The status pill: "does this song have lyrics?"

    @Test
    fun `nothing playing has no pill, the hero shows the empty state`() {
        assertEquals(NowPill.Idle, nowPill(PlayingStatus.Idle))
    }

    @Test
    fun `finding the file and looking up show a neutral pill with the moving glyph`() {
        assertEquals(NowPill.Busy(R.string.playing_finding_file), nowPill(PlayingStatus.FindingFile))
        assertEquals(NowPill.Busy(R.string.playing_looking_up), nowPill(PlayingStatus.LookingUp))
    }

    @Test
    fun `no file found gets a neutral pill whose sheet says why`() {
        assertEquals(NowPill.NoFile, nowPill(PlayingStatus.NotInLibrary))
        assertEquals(R.string.playing_no_file, NowPill.NoFile.label)
        assertEquals(R.drawable.ic_music_note, NowPill.NoFile.icon)
        assertEquals(R.string.playing_no_file_detail, NowPill.NoFile.sentence)
    }

    @Test
    fun `no file found says what Lyrify knows, never that the song is not on the phone`() {
        // Unit tests run in the module's folder.
        val file = File("src/main/res/values/strings.xml")
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string").let { nodes ->
            (0 until nodes.length).associate { (nodes.item(it) as Element).getAttribute("name") to nodes.item(it).textContent.replace("\\'", "'") }
        }
        // The pill and its sheet's title.
        assertEquals("No file found", strings["playing_no_file"])
        assertEquals(
            "Lyrify adds lyrics to song files stored on your phone, and couldn't find a file for this song. Streamed songs have no file. " +
                "If this one is on your phone, it may be in a folder your phone hides from music apps.",
            strings["playing_no_file_detail"],
        )
        assertFalse(file.readText().contains("Not on your phone"))
    }

    @Test
    fun `a song looked up shows its status's tone, icon and label, and opens its sheet`() {
        LyricsStatus.entries.forEach { status ->
            assertEquals(status.name, NowPill.Done(status.style), nowPill(PlayingStatus.Done(status)))
        }
    }

    @Test
    fun `an unreadable song list gets a neutral warning pill, not the moving glyph`() {
        assertEquals(NowPill.Unreadable, nowPill(PlayingStatus.Unreadable))
        assertEquals(R.string.ledger_unreadable_title, NowPill.Unreadable.label)
        assertEquals(R.drawable.ic_warning, NowPill.Unreadable.icon)
    }

    // The sung line in the busy pill.

    @Test
    fun `with the phone's animations off the sung line rests still at 65 percent`() {
        assertTrue(glyphSweeps(sweeping = true, animationsOn = true))
        assertFalse(glyphSweeps(sweeping = true, animationsOn = false))
        assertFalse(glyphSweeps(sweeping = false, animationsOn = true))
        assertEquals(0.65f, RESTING_FILL)
    }

    // Removing lyrics from the song sheet.

    @Test
    fun `a confirmed removal closes the sheet that asked, then removes`() {
        val steps = mutableListOf<String>()
        val removal = RemovalState(leftOnDisk = { emptySet() }) { steps += "remove $it" }
        removal.ask(listOf(PATH)) { steps += "close sheet" }
        removal.confirm()
        assertEquals(listOf("close sheet", "remove [$PATH]"), steps)
        assertNull(removal.confirming)
    }

    @Test
    fun `a cancelled removal leaves the sheet open and removes nothing`() {
        val steps = mutableListOf<String>()
        val removal = RemovalState(leftOnDisk = { emptySet() }) { steps += "remove $it" }
        removal.ask(listOf(PATH)) { steps += "close sheet" }
        removal.dismiss()
        removal.confirm()
        assertEquals(emptyList<String>(), steps)
    }

    // What the snackbar says after removing lyrics, from the Now tab or the song sheet.

    @Test
    fun `a removal says the worst that happened first`() {
        assertEquals(R.string.removal_changed, removalMessage(listOf(Removal.REMOVED, Removal.CHANGED, Removal.FAILED)))
        assertEquals(R.string.removal_failed, removalMessage(listOf(Removal.REMOVED, Removal.FAILED)))
        assertEquals(R.string.removal_done, removalMessage(listOf(Removal.ALREADY_GONE, Removal.REMOVED)))
        assertEquals(R.string.removal_gone, removalMessage(listOf(Removal.ALREADY_GONE)))
        assertEquals(R.string.removal_failed, removalMessage(listOf(Removal.NOT_REMOVABLE)))
        assertEquals(R.string.removal_failed, removalMessage(emptyList()))
    }

    @Test
    fun `the restart hint is for Samsung Music only`() {
        assertEquals(R.string.removal_done_samsung, removalMessage(listOf(Removal.REMOVED), samsungMusic = true))
        assertEquals(R.string.removal_gone, removalMessage(listOf(Removal.ALREADY_GONE), samsungMusic = true))
        assertEquals(R.string.removal_done, removalMessage(listOf(Removal.REMOVED), samsungMusic = false))
    }
}
