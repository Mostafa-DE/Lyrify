package app.lyrify.lyrics

import java.text.Normalizer
import java.util.Locale

/** One way to ask LRCLIB for a track. A null [artist] means unknown: search by title alone. */
data class TrackQuery(val artist: String?, val title: String, val album: String? = null)

/**
 * Turns the library's tags (YouTube and Telegram downloads, CD rips) into LRCLIB queries.
 * Cleaning removes what is never part of a song's name (bitrates, "(Official Video)", track
 * numbers, uploader handles); variants then drop what may or may not be (version tags, feat.).
 */
object TrackNames {
    /** At most this many queries per track: each one costs LRCLIB up to two requests. */
    const val MAX_QUERIES: Int = 5

    private val CONTROL = Regex("""\p{Cc}""")
    private val FORMAT = Regex("""\p{Cf}""")
    private val SPACES = Regex("""\s+""")
    private val DOUBLE_QUOTES = Regex("""["“”„«»]""")
    private val SINGLE_QUOTED = Regex("""^['‘’](.+)['‘’]$""")
    private val LEADING_TAGS = Regex("""^(?:\s*(?:\[[^\[\]]*]|【[^【】]*】))+\s*""")
    private val GROUP = Regex("""\s*[(\[【]([^()\[\]【】]*)[)\]】]""")
    private val TRAILING_PART = Regex("""^(.+?)\s+[-–—|]\s+([^-–—|]+)$""")
    private val HANDLE = Regex("""(?<!\w)@[\w.]+""")
    private val TRACK_NUMBER = Regex("""^(?:\d{1,3}(?:\.\s+|[)_]\s*|\s+[-–—]\s+)|0\d\s+)(?=\S)""")
    private val ARTIST_TITLE = Regex("""\s+[-–—]\s+""")
    private val TOPIC = Regex("""\s+[-–—]\s+topic$""", RegexOption.IGNORE_CASE)
    private val VEVO = Regex("""(?<=\S)\s*vevo$""", RegexOption.IGNORE_CASE)
    private val FEAT_GROUP = Regex("""\s*[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s[^()\[\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val COMBINING = Regex("""\p{M}+""")
    private val NOT_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
    private const val SEPARATORS: String = " -–—|:,;~·•_"

    /** Bracket contents and " - suffixes" that are never part of the song's name. */
    private val JUNK = Regex(
        """^(?:official\b.*|.*\b(?:video|videoclip|audio|visuali[sz]er|lyrics?|hd|hq|[48]k|\d{3,4}p|m/?v|clip)\b.*""" +
            """|(?:mp3|m4a|aac|ogg|opus|flac)?[\s_-]*\d{2,3}\s*k(?:bps)?|(?:free )?download|www\..+""" +
            """|.+\.(?:com|net|org|ru|ir|info|me|to|cc|io|fm|tv)|@[\w.]+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** " - suffixes" naming a version of the song; brackets go whatever they contain. */
    private val VERSION = Regex(
        """\b(?:remaster(?:ed)?|live|version|edit|mix|remix|demo|bonus|acoustic|mono|stereo|unplugged|session|re-?recorded|deluxe|extended)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val INSTRUMENTAL_WORD = Regex("""\b(?:instrumental|karaoke|backing track)\b""", RegexOption.IGNORE_CASE)
    private val INSTRUMENTAL_TAG = Regex(
        """[(\[][^()\[\]]*\b(?:instrumental|karaoke|backing track)\b[^()\[\]]*[)\]]|\s[-–—|]\s.*\b(?:instrumental|karaoke|backing track)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val UNKNOWN_ARTISTS = setOf("unknown", "unknown artist", "various", "various artists", "unbekannt", "sconosciuto")
    private val JUNK_ALBUMS = setOf(
        "unknown", "unknown album", "music", "musik", "musica", "audio", "download", "downloads",
        "telegram", "telegram audio", "snaptube", "youtube", "recordings", "sounds",
    )

    /**
     * Ordered LRCLIB queries, best guess first, distinct and at most [MAX_QUERIES]. With an
     * artist: cleaned names (with the album when useful), then without version tags, then
     * without feat. Without one, an "Artist - Title" title is tried both ways round, then
     * searched as a whole. Empty when no artist can be told: a title alone could match
     * anyone's song.
     */
    fun queries(artist: String?, title: String, album: String?): List<TrackQuery> {
        val cleanArtist = cleanArtist(artist)
        val cleanTitle = cleanTitle(title)
        val all = if (cleanArtist != null) {
            variants(cleanArtist, withoutArtistPrefix(cleanTitle, cleanArtist), cleanAlbum(album, artist))
        } else {
            val pair = splitArtistTitle(cleanTitle) ?: return emptyList()
            variants(pair.first, pair.second, album = null) +
                TrackQuery(pair.second, pair.first) +
                TrackQuery(null, withoutVersionTags(cleanTitle))
        }
        return all.distinctBy { normalize(it.artist.orEmpty()) to normalize(it.title) }.take(MAX_QUERIES)
    }

    private fun variants(artist: String, title: String, album: String?): List<TrackQuery> {
        val bare = withoutVersionTags(title)
        return listOf(
            TrackQuery(artist, title, album),
            TrackQuery(artist, bare),
            TrackQuery(withoutFeat(artist), withoutFeat(bare)),
        )
    }

    /** The song's name without what downloads add to it. A title that is only junk stays as it was. */
    fun cleanTitle(raw: String): String {
        val tidy = tidy(raw)
        val cleaned = tidy
            .let(::withoutQuotes)
            .let { LEADING_TAGS.replaceFirst(it, "") }
            .let(::withoutTrackNumber) // before balancing, which would drop the ")" of "7) Title"
            .let(::balanced)
            .let(::withoutJunk)
            .let { HANDLE.replace(it, " ") }
            .let(::tidy)
            .let(::trimSeparators)
        return cleaned.ifEmpty { tidy }
    }

    /** The artist without channel suffixes (" - Topic", "VEVO") and junk; null when unknown. */
    fun cleanArtist(raw: String?): String? {
        if (raw == null) return null
        val cleaned = tidy(raw)
            .let(::withoutQuotes)
            .let(::balanced)
            .let(::withoutJunk)
            .let { HANDLE.replace(it, " ") }
            .let(::tidy)
            .replace(TOPIC, "")
            .replace(VEVO, "")
            .let(::trimSeparators)
        return cleaned.takeUnless { it.isEmpty() || normalize(it) in UNKNOWN_ARTISTS }
    }

    /** The album when it can help LRCLIB: not unknown, not a folder-like name, not the artist again. */
    fun cleanAlbum(raw: String?, artist: String?): String? {
        if (raw == null) return null
        val cleaned = tidy(raw).let(::withoutQuotes).let(::balanced).let(::withoutJunk).let(::trimSeparators)
        val key = normalize(cleaned)
        val artistKey = cleanArtist(artist)?.let(::normalize)
        return cleaned.takeUnless { key.isEmpty() || key in JUNK_ALBUMS || key == artistKey }
    }

    /** Without any bracket group or " - Remastered 2011"-style suffix, except instrumental/karaoke tags. */
    fun withoutVersionTags(title: String): String {
        var text = balanced(tidy(title))
        while (true) {
            val next = GROUP.replace(text) { if (INSTRUMENTAL_WORD.containsMatchIn(it.value)) it.value else "" }
            if (next == text) break
            text = next
        }
        val cleaned = trimSeparators(tidy(withoutSuffixes(text) { VERSION.containsMatchIn(it) && !INSTRUMENTAL_WORD.containsMatchIn(it) }))
        return cleaned.ifEmpty { tidy(title) }
    }

    /** Without "(feat. X)", "[ft. X]" or a trailing "feat. X", in titles and artists. */
    fun withoutFeat(text: String): String =
        trimSeparators(tidy(FEAT_TAIL.replace(FEAT_GROUP.replace(text, ""), ""))).ifEmpty { text }

    /** The title says this is an instrumental or karaoke version, so it has no lyrics to show. */
    fun isInstrumental(title: String): Boolean = INSTRUMENTAL_TAG.containsMatchIn(balanced(tidy(title)))

    /** Comparison key: lowercase, no accents or punctuation, "&" as "and", single spaces. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(COMBINING, "")
            .lowercase(Locale.ROOT)
            .replace("ß", "ss")
            .replace("&", " and ")
            .replace(NOT_ALPHANUMERIC, " ")
            .trim()

    private fun tidy(text: String): String =
        text.replace(FORMAT, "").replace(CONTROL, " ").replace(SPACES, " ").trim()

    private fun withoutQuotes(text: String): String {
        val unquoted = text.replace(DOUBLE_QUOTES, "").trim()
        return SINGLE_QUOTED.find(unquoted)?.groupValues?.get(1)?.trim() ?: unquoted
    }

    /** Closes brackets left open at the end and drops closers that open nothing. */
    private fun balanced(text: String): String {
        val open = ArrayDeque<Char>()
        val kept = buildString {
            for (char in text) {
                when (char) {
                    '(', '[' -> {
                        open.addLast(char)
                        append(char)
                    }
                    ')', ']' -> if (open.lastOrNull() == opener(char)) {
                        open.removeLast()
                        append(char)
                    }
                    else -> append(char)
                }
            }
        }
        return kept.trimEnd() + open.reversed().joinToString("") { if (it == '(') ")" else "]" }
    }

    private fun opener(closer: Char): Char = if (closer == ')') '(' else '['

    private fun withoutJunk(text: String): String {
        val noGroups = GROUP.replace(text) { if (JUNK.matches(it.groupValues[1].trim())) "" else it.value }
        return withoutSuffixes(tidy(noGroups)) { JUNK.matches(it.trim()) }
    }

    /** Drops trailing " - part" / " | part" pieces while [drop] says so. */
    private tailrec fun withoutSuffixes(text: String, drop: (String) -> Boolean): String {
        val match = TRAILING_PART.find(text) ?: return text
        return if (drop(match.groupValues[2])) withoutSuffixes(match.groupValues[1], drop) else text
    }

    private fun withoutTrackNumber(text: String): String = TRACK_NUMBER.replaceFirst(text, "").ifBlank { text }

    private fun trimSeparators(text: String): String = text.trim { it in SEPARATORS }

    private fun splitArtistTitle(title: String): Pair<String, String>? {
        val parts = title.split(ARTIST_TITLE, limit = 2).map(::trimSeparators)
        return if (parts.size == 2 && parts.all { it.isNotEmpty() }) parts[0] to parts[1] else null
    }

    private fun withoutArtistPrefix(title: String, artist: String): String {
        val pair = splitArtistTitle(title) ?: return title
        return if (normalize(pair.first) == normalize(artist)) pair.second else title
    }
}
