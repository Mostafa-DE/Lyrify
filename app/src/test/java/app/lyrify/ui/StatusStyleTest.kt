package app.lyrify.ui

import app.lyrify.R
import app.lyrify.data.LyricsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusStyleTest {
    /** One row of the spec's status set (section 4): group, tone, icon, label, sentence (AI on). */
    private data class Row(val status: LyricsStatus, val group: SongGroup, val tone: Tone, val icon: Int, val label: Int, val sentence: Int)

    private val table = listOf(
        Row(LyricsStatus.SYNCED, SongGroup.HAS_LYRICS, Tone.SYNCED, R.drawable.ic_lyrics_fill, R.string.status_synced, R.string.status_synced_sentence),
        Row(LyricsStatus.EXISTING, SongGroup.HAS_LYRICS, Tone.SYNCED, R.drawable.ic_task_alt, R.string.status_existing, R.string.status_existing_sentence),
        Row(LyricsStatus.AI_ALIGNED, SongGroup.HAS_LYRICS, Tone.AI_SYNCED, R.drawable.ic_auto_awesome_fill, R.string.status_ai_aligned, R.string.status_ai_aligned_sentence),
        Row(
            LyricsStatus.AI_TRANSCRIBED, SongGroup.HAS_LYRICS, Tone.AI_WRITTEN, R.drawable.ic_hearing_fill,
            R.string.status_ai_transcribed, R.string.status_ai_transcribed_sentence,
        ),
        Row(LyricsStatus.NEEDS_CHECK, SongGroup.TO_CHECK, Tone.CHECK, R.drawable.ic_help_fill, R.string.status_needs_check, R.string.status_needs_check_sentence),
        Row(LyricsStatus.PLAIN_ONLY, SongGroup.WAITING, Tone.PLAIN, R.drawable.ic_hourglass_top_fill, R.string.status_plain_only, R.string.status_plain_only_sentence),
        Row(LyricsStatus.ERROR, SongGroup.WAITING, Tone.ERROR, R.drawable.ic_sync_problem, R.string.status_error, R.string.status_error_sentence),
        Row(LyricsStatus.AI_FAILED, SongGroup.WAITING, Tone.ERROR, R.drawable.ic_replay, R.string.status_ai_failed, R.string.status_ai_failed_sentence),
        Row(LyricsStatus.WRITING, SongGroup.WAITING, Tone.NONE, R.drawable.ic_save, R.string.status_writing, R.string.status_writing_sentence),
        Row(LyricsStatus.NOT_FOUND, SongGroup.NO_LYRICS, Tone.NONE, R.drawable.ic_search_off, R.string.status_not_found, R.string.status_not_found_sentence_ai_on),
        Row(LyricsStatus.INSTRUMENTAL, SongGroup.NO_LYRICS, Tone.INSTRUMENTAL, R.drawable.ic_piano, R.string.status_instrumental, R.string.status_instrumental_sentence),
        Row(
            LyricsStatus.AI_INSTRUMENTAL, SongGroup.NO_LYRICS, Tone.INSTRUMENTAL, R.drawable.ic_voice_over_off,
            R.string.status_ai_instrumental, R.string.status_ai_instrumental_sentence,
        ),
        Row(LyricsStatus.AI_REJECTED, SongGroup.NO_LYRICS, Tone.NONE, R.drawable.ic_do_not_disturb_on, R.string.status_ai_rejected, R.string.status_ai_rejected_sentence),
        Row(
            LyricsStatus.DURATION_MISMATCH, SongGroup.NO_LYRICS, Tone.NONE, R.drawable.ic_difference,
            R.string.status_duration_mismatch, R.string.status_duration_mismatch_sentence,
        ),
        Row(LyricsStatus.UNIDENTIFIED, SongGroup.NO_LYRICS, Tone.NONE, R.drawable.ic_person_off, R.string.status_unidentified, R.string.status_unidentified_sentence),
        Row(LyricsStatus.REMOVED, SongGroup.NO_LYRICS, Tone.NONE, R.drawable.ic_delete, R.string.status_removed, R.string.status_removed_sentence),
    )

    @Test
    fun `the table covers all 16 statuses once`() {
        assertEquals(16, LyricsStatus.entries.size)
        assertEquals(16, table.size)
        assertEquals(LyricsStatus.entries.toSet(), table.map { it.status }.toSet())
    }

    @Test
    fun `each status has its group, tone, icon, label and sentence`() {
        table.forEach { row ->
            val style = row.status.style
            assertEquals(row.status.name, row.group, row.status.group)
            assertEquals(row.status.name, row.group, style.group)
            assertEquals(row.status.name, row.tone, style.tone)
            assertEquals(row.status.name, row.icon, style.icon)
            assertEquals(row.status.name, row.label, style.label)
            assertEquals(row.status.name, row.sentence, style.sentence(PlainTiming.AI_ON))
        }
    }

    @Test
    fun `one label per status`() {
        assertEquals(16, LyricsStatus.entries.map { it.style.label }.toSet().size)
    }

    @Test
    fun `plain lyrics say what becomes of them, AI on, off or not on this phone`() {
        val plain = LyricsStatus.PLAIN_ONLY.style
        assertEquals(R.string.status_plain_only_sentence, plain.sentence(PlainTiming.AI_ON))
        assertEquals(R.string.status_plain_only_sentence_ai_off, plain.sentence(PlainTiming.AI_OFF))
        assertEquals(R.string.status_plain_only_sentence_no_ai, plain.sentence(PlainTiming.NO_AI))
    }

    @Test
    fun `songs with no lyrics online mention the AI only while it is on`() {
        val notFound = LyricsStatus.NOT_FOUND.style
        assertEquals(R.string.status_not_found_sentence_ai_on, notFound.sentence(PlainTiming.AI_ON))
        assertEquals(R.string.status_not_found_sentence, notFound.sentence(PlainTiming.AI_OFF))
        assertEquals(R.string.status_not_found_sentence, notFound.sentence(PlainTiming.NO_AI))
    }

    @Test
    fun `other statuses read the same whatever the AI does`() {
        LyricsStatus.entries.filter { it != LyricsStatus.PLAIN_ONLY && it != LyricsStatus.NOT_FOUND }.forEach { status ->
            assertEquals(status.name, 1, PlainTiming.entries.map { status.style.sentence(it) }.toSet().size)
        }
    }

    @Test
    fun `groups hold their statuses in the spec's order, each status in one group`() {
        assertEquals(
            listOf(LyricsStatus.SYNCED, LyricsStatus.EXISTING, LyricsStatus.AI_ALIGNED, LyricsStatus.AI_TRANSCRIBED),
            SongGroup.HAS_LYRICS.statuses,
        )
        assertEquals(listOf(LyricsStatus.NEEDS_CHECK), SongGroup.TO_CHECK.statuses)
        assertEquals(listOf(LyricsStatus.PLAIN_ONLY, LyricsStatus.ERROR, LyricsStatus.AI_FAILED, LyricsStatus.WRITING), SongGroup.WAITING.statuses)
        assertEquals(
            listOf(
                LyricsStatus.NOT_FOUND, LyricsStatus.INSTRUMENTAL, LyricsStatus.AI_INSTRUMENTAL, LyricsStatus.AI_REJECTED,
                LyricsStatus.DURATION_MISMATCH, LyricsStatus.UNIDENTIFIED, LyricsStatus.REMOVED,
            ),
            SongGroup.NO_LYRICS.statuses,
        )
        assertEquals(LyricsStatus.entries.sorted(), SongGroup.entries.flatMap { it.statuses }.sorted())
    }

    @Test
    fun `groups have their label, tone and icon`() {
        assertEquals(
            listOf(R.string.group_has_lyrics, R.string.group_to_check, R.string.group_waiting, R.string.group_no_lyrics),
            SongGroup.entries.map { it.label },
        )
        assertEquals(listOf(Tone.SYNCED, Tone.CHECK, Tone.PLAIN, Tone.NONE), SongGroup.entries.map { it.tone })
        assertEquals(
            listOf(R.drawable.ic_lyrics_fill, R.drawable.ic_help_fill, R.drawable.ic_hourglass_top_fill, R.drawable.ic_search_off),
            SongGroup.entries.map { it.icon },
        )
    }
}
