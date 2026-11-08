package app.lyrify.tones

/** What the editor's preview does after an event. */
sealed interface PreviewCommand {
    /** Plays [source] from its start ([TonePlayer.play]). */
    data class Play(val source: PreviewSource.Window) : PreviewCommand

    /** Goes on playing, from [source]'s start now, with its length and fades ([TonePlayer.jump]). */
    data class Jump(val source: PreviewSource.Window) : PreviewCommand

    data object Stop : PreviewCommand
}

/** The preview's next [state], and [command] for the player; null when the player has nothing to do. */
data class PreviewStep(val state: PreviewState, val command: PreviewCommand? = null)

/**
 * The editor's preview, pure: [playing] is the part it plays, null when it is stopped. While it plays, a changed part
 * (the window or its start dragged, a lyric line, a second earlier or later, another length or fade) jumps it to the new
 * start and it keeps playing; a stopped preview stays stopped. During a drag it jumps at most every [JUMP_EVERY_MS], so
 * it does not stutter, and once more where the drag is let go. [jumpedAtMs] is when it last jumped, on the caller's
 * monotonic clock.
 */
data class PreviewState(val playing: PreviewSource.Window? = null, val jumpedAtMs: Long? = null) {
    fun onPlay(source: PreviewSource.Window): PreviewStep = PreviewStep(PreviewState(playing = source), PreviewCommand.Play(source))

    fun onStop(): PreviewStep = PreviewStep(PreviewState(), PreviewCommand.Stop)

    /** The player stopped by itself: the part's end, another app's sound, a file it could not play. */
    fun onEnded(): PreviewStep = PreviewStep(PreviewState())

    /** The part is [source] now, at [nowMs]; [dragging] while a finger moves it, false once it is let go. */
    fun onPartChanged(source: PreviewSource.Window, nowMs: Long, dragging: Boolean): PreviewStep {
        if (playing == null || source == playing) return PreviewStep(this)
        val last = jumpedAtMs
        // Held back: a later move, or letting go, jumps to the part as it is then.
        if (dragging && last != null && nowMs - last < JUMP_EVERY_MS) return PreviewStep(this)
        return PreviewStep(PreviewState(source, nowMs), PreviewCommand.Jump(source))
    }

    companion object {
        /** A drag's jumps at most this often: each one seeks the song. */
        const val JUMP_EVERY_MS: Long = 150
    }
}
