package app.lyrify.ui

import androidx.compose.runtime.saveable.SaverScope
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.LrcLine
import app.lyrify.tones.DefaultOutcome
import app.lyrify.tones.FakeSounds
import app.lyrify.tones.SaveFailure
import app.lyrify.tones.ToneDefaults
import app.lyrify.tones.ToneEdit
import app.lyrify.tones.ToneKind
import app.lyrify.tones.ToneRequest
import app.lyrify.tones.ToneWindow
import app.lyrify.tones.tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Tones tab's logic and copy: made-up songs, placeholder lyric text only. */
class TonesUiTest {
    private val fourMinutes = 240_000L
    private val lines = listOf(LrcLine(12_000, "line a"), LrcLine(41_000, "line b"), LrcLine(44_000, "line c"))
    private val saver = SaverScope { true }

    // The draft: default lengths per kind

    @Test
    fun `a new tone is a 30 second ringtone from the start, both fades on`() {
        val draft = ToneDraft(songDurationMs = fourMinutes)
        assertEquals(ToneKind.RINGTONE, draft.kind)
        assertEquals(ToneWindow(0, 30_000), draft.window(emptyList()))
        assertEquals(1_500L, draft.fadeInMs)
        assertEquals(1_500L, draft.fadeOutMs)
    }

    @Test
    fun `the kind sets its default length until the user picks one`() {
        assertEquals(60_000L, ToneDraft(fourMinutes).withKind(ToneKind.ALARM).window(emptyList()).lengthMs)
        assertEquals(15_000L, ToneDraft(fourMinutes).withKind(ToneKind.NOTIFICATION).window(emptyList()).lengthMs)
        assertEquals(30_000L, ToneDraft(fourMinutes).withKind(ToneKind.ALARM).withKind(ToneKind.RINGTONE).window(emptyList()).lengthMs)
        assertEquals(45_000L, ToneDraft(fourMinutes).withLength(45_000).withKind(ToneKind.ALARM).window(emptyList()).lengthMs)
    }

    @Test
    fun `fades switched off are no fades`() {
        val draft = ToneDraft(fourMinutes).copy(fadeIn = false, fadeOut = false)
        assertEquals(0L, draft.fadeInMs)
        assertEquals(0L, draft.fadeOutMs)
    }

    @Test
    fun `a lyric line starts the tone, and only then is End of line offered`() {
        val fromLyric = ToneDraft(fourMinutes).startingAt(12_000, fromLyric = true)
        assertTrue(fromLyric.lineEndOffered(lines))
        assertEquals(ToneWindow(12_000, 32_000), fromLyric.withLineEnd().window(lines))
        assertFalse(ToneDraft(fourMinutes).startingAt(12_000).lineEndOffered(lines))
        assertFalse(fromLyric.lineEndOffered(emptyList()))
    }

    @Test
    fun `moving the part, its start or its length drops End of line`() {
        val lineEnd = ToneDraft(fourMinutes).startingAt(12_000, fromLyric = true).withLineEnd()
        assertFalse(lineEnd.movedTo(20_000).lineEnd)
        assertFalse(lineEnd.nudged(1_000).lineEnd)
        assertFalse(lineEnd.withLength(15_000).lineEnd)
        assertTrue(lineEnd.startingAt(41_000, fromLyric = true).lineEnd)
    }

    @Test
    fun `dragging the whole part keeps its length, its start shortens it near the end`() {
        assertEquals(ToneWindow(210_000, 30_000), ToneDraft(fourMinutes).movedTo(230_000).window(emptyList()))
        assertEquals(ToneWindow(230_000, 10_000), ToneDraft(fourMinutes).startingAt(230_000).window(emptyList()))
    }

    @Test
    fun `TalkBack moves the start a second either way, never before the song`() {
        assertEquals(0L, ToneDraft(fourMinutes).nudged(-1_000).startMs)
        assertEquals(73_000L, ToneDraft(fourMinutes).startingAt(72_000).nudged(1_000).startMs)
        assertEquals(71_000L, ToneDraft(fourMinutes).startingAt(72_000).nudged(-1_000).startMs)
    }

    @Test
    fun `a song over 20 minutes offers its first 20`() {
        val long = ToneDraft(25 * 60_000L)
        assertTrue(long.limited)
        assertEquals(ToneWindow.MAX_SOURCE_MS, long.durationMs)
        assertEquals(ToneWindow.MAX_SOURCE_MS - 30_000, long.movedTo(24 * 60_000L).startMs)
        assertFalse(ToneDraft(fourMinutes).limited)
    }

    @Test
    fun `the request is the part, the fades and the kind, named after the cleaned title`() {
        val song = LedgerEntry(path = "/music/Paper Lanterns.mp3", title = "Paper Lanterns (MP3_160K)", durationMs = fourMinutes, status = LyricsStatus.SYNCED, updatedAt = 0)
        val draft = ToneDraft(fourMinutes).startingAt(72_000).withKind(ToneKind.ALARM).copy(fadeOut = false)
        assertEquals(
            ToneRequest(
                songPath = "/music/Paper Lanterns.mp3",
                songTitle = "Paper Lanterns",
                window = ToneWindow(72_000, 60_000),
                fadeInMs = 1_500,
                fadeOutMs = 0,
                kind = ToneKind.ALARM,
            ),
            draft.request(song, emptyList()),
        )
    }

    @Test
    fun `a draft survives a turn of the phone`() {
        val draft = ToneDraft(fourMinutes).startingAt(12_000, fromLyric = true).withLineEnd().withKind(ToneKind.NOTIFICATION).copy(fadeIn = false)
        val saved = requireNotNull(with(ToneDraft.Saver) { saver.save(draft) })
        assertEquals(draft, ToneDraft.Saver.restore(saved))
    }

    // Times and labels

    @Test
    fun `times read as minutes and seconds`() {
        assertEquals("0:00", toneTime(0))
        assertEquals("0:30", toneTime(30_000))
        assertEquals("0:59", toneTime(59_999))
        assertEquals("1:12", toneTime(72_000))
        assertEquals("19:59", toneTime(1_199_000))
    }

    @Test
    fun `each kind has its label, icon and menu item`() {
        assertEquals(listOf(R.string.tone_kind_ringtone, R.string.tone_kind_alarm, R.string.tone_kind_notification), ToneKind.entries.map(::kindLabel))
        assertEquals(listOf(R.drawable.ic_ring_volume, R.drawable.ic_alarm, R.drawable.ic_notifications), ToneKind.entries.map(::kindIcon))
        assertEquals(listOf(R.string.tones_set_as_ringtone, R.string.tones_set_as_alarm, R.string.tones_set_as_notification), ToneKind.entries.map(::setAsLabel))
    }

    @Test
    fun `save failures have their message`() {
        assertEquals(R.string.tones_song_unreadable, saveFailureMessage(SaveFailure.SONG_UNREADABLE))
        assertEquals(R.string.tones_not_saved, saveFailureMessage(SaveFailure.NOT_SAVED))
    }

    @Test
    fun `a delete or rename says what happened, and a tone no longer there says so`() {
        assertEquals(R.string.tones_deleted, deleteMessage(ToneEdit.Done(null)))
        assertEquals(R.string.tones_gone, deleteMessage(ToneEdit.Gone))
        assertEquals(R.string.tones_not_deleted, deleteMessage(ToneEdit.Failed))
        assertNull(renameMessage(ToneEdit.Done(tone(41))))
        assertEquals(R.string.tones_gone, renameMessage(ToneEdit.Gone))
        assertEquals(R.string.tones_not_renamed, renameMessage(ToneEdit.Failed))
    }

    // The set-default sheet: its copy for each state

    @Test
    fun `after saving, the sheet asks about the kind saved, with its name`() {
        assertEquals(SheetCopy(R.string.tones_ask_ringtone, R.string.tones_saved_as, SheetAction.SET, R.string.tones_not_now), sheetCopy(ToneKind.RINGTONE, DefaultSheet.Step.ASK))
        assertEquals(R.string.tones_ask_alarm, sheetCopy(ToneKind.ALARM, DefaultSheet.Step.ASK).title)
        assertEquals(R.string.tones_ask_notification, sheetCopy(ToneKind.NOTIFICATION, DefaultSheet.Step.ASK).title)
    }

    @Test
    fun `without the permission the sheet gives the reason and opens settings`() {
        listOf(DefaultSheet.Step.PERMISSION, DefaultSheet.Step.WAITING).forEach { step ->
            assertEquals(SheetCopy(R.string.tones_ask_ringtone, R.string.tones_permission_why, SheetAction.OPEN_SETTINGS, R.string.tones_not_now), sheetCopy(ToneKind.RINGTONE, step))
        }
    }

    @Test
    fun `a ringtone set mentions a second SIM and opens the sound settings`() {
        assertEquals(
            SheetCopy(R.string.tones_done_ringtone, R.string.tones_two_sims, SheetAction.OPEN_SOUND_SETTINGS, R.string.tones_done),
            sheetCopy(ToneKind.RINGTONE, DefaultSheet.Step.DONE),
        )
    }

    @Test
    fun `an alarm sound set says the Clock may keep its own, and opens the Clock`() {
        assertEquals(
            SheetCopy(R.string.tones_done_alarm, R.string.tones_alarm_clock, SheetAction.OPEN_CLOCK, R.string.tones_done),
            sheetCopy(ToneKind.ALARM, DefaultSheet.Step.DONE),
        )
    }

    @Test
    fun `a notification sound set says only that`() {
        assertEquals(SheetCopy(R.string.tones_done_notification, null, null, R.string.tones_done), sheetCopy(ToneKind.NOTIFICATION, DefaultSheet.Step.DONE))
    }

    @Test
    fun `a sound not set says so, and without a settings screen points to the sound settings`() {
        assertEquals(SheetCopy(R.string.tones_not_set_title, R.string.tones_not_set, null, R.string.tones_done), sheetCopy(ToneKind.ALARM, DefaultSheet.Step.NOT_SET))
        assertEquals(
            SheetCopy(R.string.tones_not_set_title, R.string.tones_no_permission_screen, SheetAction.OPEN_SOUND_SETTINGS, R.string.tones_done),
            sheetCopy(ToneKind.RINGTONE, DefaultSheet.Step.NO_SCREEN),
        )
    }

    @Test
    fun `each outcome leads to its step`() {
        assertEquals(DefaultSheet.Step.WAITING, stepAfter(DefaultOutcome.AskedPermission))
        assertEquals(DefaultSheet.Step.NO_SCREEN, stepAfter(DefaultOutcome.NoPermissionScreen))
        assertEquals(DefaultSheet.Step.DONE, stepAfter(DefaultOutcome.Set(ToneKind.RINGTONE)))
        assertEquals(DefaultSheet.Step.DONE, stepAfter(DefaultOutcome.AlarmSet("Glass Harbor (Lyrify)")))
        assertEquals(DefaultSheet.Step.NOT_SET, stepAfter(DefaultOutcome.NotSet))
    }

    // The set-default flow

    private val ask = DefaultSheet(41, ToneKind.RINGTONE, DefaultSheet.Step.ASK)

    @Test
    fun `Set without the permission shows the reason first and opens nothing`() {
        val sounds = FakeSounds(writable = false)
        assertEquals(DefaultSheet.Step.PERMISSION, ask.tapped(ToneDefaults(sounds), tone(41)).step)
        assertEquals(emptyList<String>(), sounds.calls)
    }

    @Test
    fun `Open settings opens Modify system settings and waits`() {
        val sounds = FakeSounds(writable = false)
        assertEquals(DefaultSheet.Step.WAITING, ask.copy(step = DefaultSheet.Step.PERMISSION).tapped(ToneDefaults(sounds), tone(41)).step)
        assertEquals(listOf("openWriteSettings"), sounds.calls)
    }

    @Test
    fun `back from settings, an allowed sound is set, otherwise the reason shows again`() {
        val waiting = ask.copy(step = DefaultSheet.Step.WAITING)
        val allowed = FakeSounds(writable = true)
        assertEquals(DefaultSheet.Step.DONE, waiting.resumed(ToneDefaults(allowed), tone(41)).step)
        assertEquals(listOf("setDefault RINGTONE 41"), allowed.calls)

        val refused = FakeSounds(writable = false)
        assertEquals(DefaultSheet.Step.PERMISSION, waiting.resumed(ToneDefaults(refused), tone(41)).step)
        assertEquals(emptyList<String>(), refused.calls)
    }

    @Test
    fun `Set with the permission sets the right kind at once`() {
        val sounds = FakeSounds(writable = true)
        val alarm = DefaultSheet(41, ToneKind.ALARM, DefaultSheet.Step.ASK)
        assertEquals(DefaultSheet.Step.DONE, alarm.tapped(ToneDefaults(sounds), tone(41)).step)
        assertEquals(listOf("setDefault ALARM 41"), sounds.calls)
    }

    @Test
    fun `a sheet not waiting is left as it is when Lyrify comes back`() {
        val sounds = FakeSounds(writable = true)
        DefaultSheet.Step.entries.filter { it != DefaultSheet.Step.WAITING }.forEach { step ->
            assertEquals(ask.copy(step = step), ask.copy(step = step).resumed(ToneDefaults(sounds), tone(41)))
        }
        assertEquals(emptyList<String>(), sounds.calls)
    }

    @Test
    fun `the sheet survives a turn of the phone, and no sheet stays none`() {
        val sheet = DefaultSheet(41, ToneKind.NOTIFICATION, DefaultSheet.Step.WAITING)
        val saved = requireNotNull(with(DefaultSheet.Saver) { saver.save(sheet) })
        assertEquals(sheet, DefaultSheet.Saver.restore(saved))
        val none = with(DefaultSheet.Saver) { saver.save(null) }
        assertNull(none?.let { DefaultSheet.Saver.restore(it) })
    }
}
