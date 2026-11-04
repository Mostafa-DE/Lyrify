package app.lyrify.overnight

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The AI's own log, in app-private files, so the next failure can be read on the phone without logcat: one line per
 * event of the overnight runs (start, stages and their times, waits for the heat, stops and why, resume points,
 * deaths, results), each with its local time. Never lyrics: callers log titles, stages, times and counts only.
 * [file] grows to half of [maxBytes], then becomes `<file>.1` (replacing the one before), so the two hold about
 * [maxBytes]. Thread safe within the process; a line that cannot be written is dropped (the log never stops a run).
 */
class AiRunLog(
    private val file: File,
    private val clock: () -> Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val maxBytes: Long = MAX_BYTES,
) {
    private val older = File(file.path + ".1")
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun append(event: String) {
        val line = "${time.format(Instant.ofEpochMilli(clock()).atZone(zone))} ${event.replace('\n', ' ').replace('\r', ' ')}\n"
        try {
            file.parentFile?.mkdirs()
            if (file.length() >= maxBytes / 2) Files.move(file.toPath(), older.toPath(), StandardCopyOption.REPLACE_EXISTING)
            file.appendText(line)
        } catch (_: IOException) {
            // Dropped: a full or unwritable disk must not stop the run it describes.
        }
    }

    /** The last [lines] lines, oldest first. */
    @Synchronized
    fun tail(lines: Int): List<String> = (read(older) + read(file)).takeLast(lines)

    private fun read(file: File): List<String> = try {
        if (file.isFile) file.readLines() else emptyList()
    } catch (_: IOException) {
        emptyList()
    }

    companion object {
        /** About 1,500 lines, a week or two of nights. */
        const val MAX_BYTES: Long = 200L * 1024
    }
}
