package app.lyrify.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.lyrify.R
import app.lyrify.data.LyricsStatus

/** The colour family of a status; [LocalStatusColors] gives its colours in the active theme. */
enum class Tone { SYNCED, AI_SYNCED, AI_WRITTEN, PLAIN, CHECK, NONE, INSTRUMENTAL, ERROR }

/**
 * How [status] reads everywhere: a short [label] on pills, badges and chips, an [icon] and a [tone]; its [sentence]
 * only in the song sheet. The same words wherever the status shows.
 */
@Immutable
data class StatusStyle(val status: LyricsStatus, @StringRes val label: Int, @DrawableRes val icon: Int, val tone: Tone) {
    val group: SongGroup get() = status.group

    /** The sheet's sentence; for plain lyrics and songs with none online it says what the AI does ([timing]). */
    @StringRes
    fun sentence(timing: PlainTiming): Int = when (status) {
        LyricsStatus.SYNCED -> R.string.status_synced_sentence
        LyricsStatus.EXISTING -> R.string.status_existing_sentence
        LyricsStatus.AI_ALIGNED -> R.string.status_ai_aligned_sentence
        LyricsStatus.AI_TRANSCRIBED -> R.string.status_ai_transcribed_sentence
        LyricsStatus.NEEDS_CHECK -> R.string.status_needs_check_sentence
        LyricsStatus.PLAIN_ONLY -> when (timing) {
            PlainTiming.AI_ON -> R.string.status_plain_only_sentence
            PlainTiming.AI_OFF -> R.string.status_plain_only_sentence_ai_off
            PlainTiming.NO_AI -> R.string.status_plain_only_sentence_no_ai
        }
        LyricsStatus.ERROR -> R.string.status_error_sentence
        LyricsStatus.AI_FAILED -> R.string.status_ai_failed_sentence
        LyricsStatus.WRITING -> R.string.status_writing_sentence
        LyricsStatus.NOT_FOUND -> if (timing == PlainTiming.AI_ON) R.string.status_not_found_sentence_ai_on else R.string.status_not_found_sentence
        LyricsStatus.INSTRUMENTAL -> R.string.status_instrumental_sentence
        LyricsStatus.AI_INSTRUMENTAL -> R.string.status_ai_instrumental_sentence
        LyricsStatus.AI_REJECTED -> R.string.status_ai_rejected_sentence
        LyricsStatus.DURATION_MISMATCH -> R.string.status_duration_mismatch_sentence
        LyricsStatus.UNIDENTIFIED -> R.string.status_unidentified_sentence
        LyricsStatus.REMOVED -> R.string.status_removed_sentence
    }
}

/** The spec's status set: one label, one icon and one tone per status. Only "To check", errors and the AI's work stand out. */
val LyricsStatus.style: StatusStyle
    get() = when (this) {
        LyricsStatus.SYNCED -> StatusStyle(this, R.string.status_synced, R.drawable.ic_lyrics_fill, Tone.SYNCED)
        LyricsStatus.EXISTING -> StatusStyle(this, R.string.status_existing, R.drawable.ic_task_alt, Tone.SYNCED)
        LyricsStatus.AI_ALIGNED -> StatusStyle(this, R.string.status_ai_aligned, R.drawable.ic_auto_awesome_fill, Tone.AI_SYNCED)
        LyricsStatus.AI_TRANSCRIBED -> StatusStyle(this, R.string.status_ai_transcribed, R.drawable.ic_hearing_fill, Tone.AI_WRITTEN)
        LyricsStatus.NEEDS_CHECK -> StatusStyle(this, R.string.status_needs_check, R.drawable.ic_help_fill, Tone.CHECK)
        LyricsStatus.PLAIN_ONLY -> StatusStyle(this, R.string.status_plain_only, R.drawable.ic_hourglass_top_fill, Tone.PLAIN)
        LyricsStatus.ERROR -> StatusStyle(this, R.string.status_error, R.drawable.ic_sync_problem, Tone.ERROR)
        LyricsStatus.AI_FAILED -> StatusStyle(this, R.string.status_ai_failed, R.drawable.ic_replay, Tone.ERROR)
        LyricsStatus.WRITING -> StatusStyle(this, R.string.status_writing, R.drawable.ic_save, Tone.NONE)
        LyricsStatus.NOT_FOUND -> StatusStyle(this, R.string.status_not_found, R.drawable.ic_search_off, Tone.NONE)
        LyricsStatus.INSTRUMENTAL -> StatusStyle(this, R.string.status_instrumental, R.drawable.ic_piano, Tone.INSTRUMENTAL)
        LyricsStatus.AI_INSTRUMENTAL -> StatusStyle(this, R.string.status_ai_instrumental, R.drawable.ic_voice_over_off, Tone.INSTRUMENTAL)
        LyricsStatus.AI_REJECTED -> StatusStyle(this, R.string.status_ai_rejected, R.drawable.ic_do_not_disturb_on, Tone.NONE)
        LyricsStatus.DURATION_MISMATCH -> StatusStyle(this, R.string.status_duration_mismatch, R.drawable.ic_difference, Tone.NONE)
        LyricsStatus.UNIDENTIFIED -> StatusStyle(this, R.string.status_unidentified, R.drawable.ic_person_off, Tone.NONE)
        LyricsStatus.REMOVED -> StatusStyle(this, R.string.status_removed, R.drawable.ic_delete, Tone.NONE)
    }
