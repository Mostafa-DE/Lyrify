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
    fun `a stream gets a neutral pill whose sheet says why`() {
        assertEquals(NowPill.NotOnPhone, nowPill(PlayingStatus.NotInLibrary))
        assertEquals(R.string.playing_not_in_library, NowPill.NotOnPhone.label)
        assertEquals(R.drawable.ic_music_note, NowPill.NotOnPhone.icon)
        assertEquals(R.string.playing_not_in_library_detail, NowPill.NotOnPhone.sentence)
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
