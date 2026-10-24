package app.lyrify.media

import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.data.recorded
import app.lyrify.lyrics.LocalTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {
    private val path = "/music/Nemo.mp3"
    private val ledger = LedgerState().put(recorded(null, LocalTrack(path, "Nemo", "Nightwish", null, 276_000), LyricsStatus.SYNCED, 1, 5))

    private var connection: ListenerConnection? = null

    @After
    fun reset() {
        connection?.let(NowPlaying::disconnect)
    }

    @Test
    fun `nothing playing`() {
        assertEquals(PlayingStatus.Idle, playingStatus(null, ledger))
    }

    @Test
    fun `a track still being looked for in the library`() {
        assertEquals(PlayingStatus.FindingFile, playingStatus(CurrentTrack("Nemo", "Nightwish"), ledger))
    }

    @Test
    fun `a track that is not a local file`() {
        assertEquals(PlayingStatus.NotInLibrary, playingStatus(CurrentTrack("Nemo", "Nightwish", resolved = true), ledger))
    }

    @Test
    fun `a local track shows its ledger status, or that it is being looked up`() {
        assertEquals(PlayingStatus.Done(LyricsStatus.SYNCED), playingStatus(CurrentTrack("Nemo", "Nightwish", listOf(path), resolved = true), ledger))
        assertEquals(PlayingStatus.LookingUp, playingStatus(CurrentTrack("Other", null, listOf("/music/Other.mp3"), resolved = true), ledger))
    }

    @Test
    fun `a track with several copies shows the first copy that has a status`() {
        val copies = CurrentTrack("Nemo", "Nightwish", listOf("/music/copy/Nemo.mp3", path), resolved = true)
        assertEquals(PlayingStatus.Done(LyricsStatus.SYNCED), playingStatus(copies, ledger))
    }

    @Test
    fun `only the instance that connected last can disconnect`() {
        val old = NowPlaying.connect()
        val new = NowPlaying.connect().also { connection = it }
        NowPlaying.setTrack(new, CurrentTrack("Nemo", "Nightwish"))
        NowPlaying.disconnect(old)
        assertTrue(NowPlaying.connected.value)
        assertEquals(CurrentTrack("Nemo", "Nightwish"), NowPlaying.track.value)
        NowPlaying.disconnect(new)
        assertFalse(NowPlaying.connected.value)
        assertEquals(null, NowPlaying.track.value)
    }

    @Test
    fun `a connection that ended can no longer change what the screen shows`() {
        val old = NowPlaying.connect()
        NowPlaying.disconnect(old)

        NowPlaying.setTrack(old, CurrentTrack("Late", "Write"))
        NowPlaying.setSweep(old, SweepProgress(1, 2, paused = false))

        assertEquals(null, NowPlaying.track.value)
        assertEquals(null, NowPlaying.sweep.value)
    }

    @Test
    fun `an older connection cannot overwrite the newer one`() {
        val old = NowPlaying.connect()
        val new = NowPlaying.connect().also { connection = it }
        NowPlaying.setSweep(new, SweepProgress(3, 10, paused = true))

        NowPlaying.setTrack(old, CurrentTrack("Old", null))
        NowPlaying.setSweep(old, SweepProgress(0, 0, paused = false))

        assertEquals(null, NowPlaying.track.value)
        assertEquals(SweepProgress(3, 10, paused = true), NowPlaying.sweep.value)
        NowPlaying.disconnect(new)
        assertEquals(null, NowPlaying.sweep.value)
    }
}
