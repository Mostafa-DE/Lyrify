package app.lyrify.lyrics

import java.text.Normalizer
import java.util.Locale

/** One way to ask LRCLIB for a track. A null [artist] means unknown: search by title alone. */
data class TrackQuery(val artist: String?, val title: String, val album: String? = null)

/**
 * Turns the library's tags (YouTube and Telegram downloads, CD rips) into LRCLIB queries.
 * Cleaning removes what is never part of a song's name (bitrates, "(Official Video)", track
 * numbers, uploader handles); variants then drop what may or may not be (version tags, feat.).
 * When the tags say too little, the file name is read the same way ([fileTitle]).
 */
object TrackNames {
    /** At most this many queries per track: each one costs LRCLIB up to two requests. */
    const val MAX_QUERIES: Int = 5

    /** At most this many loose (`q=`) searches per track ([looseQueries]): one request each. */
    const val MAX_LOOSE_QUERIES: Int = 2

    /** Requests of one lookup at most, each asked again while LRCLIB is busy: two per query, one per loose search. */
    const val MAX_REQUESTS: Int = 2 * MAX_QUERIES + MAX_LOOSE_QUERIES

    private val CONTROL = Regex("""\p{Cc}""")
    private val FORMAT = Regex("""\p{Cf}""")
    private val SPACES = Regex("""\s+""")
    private val DOUBLE_QUOTES = Regex("""["“”„«»]""")
    private val SINGLE_QUOTED = Regex("""^['‘’](.+)['‘’]$""")
    private val LEADING_TAGS = Regex("""^(?:\s*(?:\[[^\[\]]*]|【[^【】]*】))+\s*""")
    private val GROUP = Regex("""\s*[(\[【]([^()\[\]【】]*)[)\]】]""")
    private val TRAILING_PART = Regex("""^(.+?)\s+[-–—|]\s+([^-–—|]+)$""")
    private val HANDLE = Regex("""(?<!\w)@[\w.]+""")
    /**
     * In a title tag: "04. ", "7) ", "12_", "04 - ", "05- " and "05-Title" (a leading zero). Never "05 Lanterns",
     * "2-Step"-like names, "1979" or "1-555-010-0199": a tag's number may be part of the song's name.
     */
    private val TRACK_NUMBER = Regex("""^(?:\d{1,3}(?:\.\s+|[)_]\s*|\s+[-–—]\s+|-\s+)|0\d-(?=\p{L}))(?=\S)""")

    /** In a file name, also "04 " (a leading zero) and "5-Title": file names number their tracks far more often. */
    private val FILE_TRACK_NUMBER = Regex("""^(?:\d{1,3}(?:\.\s+|[)_]\s*|\s+[-–—]\s+|-(?:\s+|(?=\p{L})))|0\d\s+)(?=\S)""")
    private val ARTIST_TITLE = Regex("""\s+[-–—]\s+""")
    private val TOPIC = Regex("""\s+[-–—]\s+topic$""", RegexOption.IGNORE_CASE)
    private val VEVO = Regex("""(?<=\S)\s*vevo$""", RegexOption.IGNORE_CASE)
    private val FEAT_GROUP = Regex("""\s*[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s[^()\[\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val COMBINING = Regex("""\p{M}+""")
    private val NOT_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
    private val LETTER = Regex("""\p{L}""")
    private const val SEPARATORS: String = " -–—|:,;~·•_"

    /** Snaptube writes ' and & as "_" in file names: a lost character, so a word boundary, never a letter. */
    private const val LOST_CHARACTER: Char = '_'

    /** A lost character between spaces was an "&" ("A _ B"); one inside a word, an apostrophe ("Don_t"). */
    private val LOST_AMPERSAND = Regex("""(?<=\s)_(?=\s)""")

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
     * searched as a whole. When the tags say too little (no artist, or a title that is only
     * the file name), the same follows for the [fileName]'s [fileTitle], after the title's.
     * An artist tag is always used as it is: an artist named only in the title or the file
     * name next to another artist tag (an uploader's) is left to the user's check. Empty
     * when no artist can be told: a title alone could match anyone's song.
     */
    fun queries(artist: String?, title: String, album: String?, fileName: String? = null): List<TrackQuery> {
        val cleanArtist = cleanArtist(artist)
        val fromFile = fileName?.takeIf { cleanArtist == null || normalize(title) == normalize(baseName(it)) }?.let(::fileTitle)
        val all = fromTags(cleanArtist, cleanTitle(title), album, artist) + fromFile?.let { fromTags(cleanArtist, it, album, artist) }.orEmpty()
        return all.distinctBy { normalize(it.artist.orEmpty()) to normalize(it.title) }.take(MAX_QUERIES)
    }

    private fun fromTags(cleanArtist: String?, cleanTitle: String, album: String?, artist: String?): List<TrackQuery> {
        if (cleanArtist != null) return variants(cleanArtist, withoutArtistPrefix(cleanTitle, cleanArtist), cleanAlbum(album, artist))
        val pair = splitArtistTitle(cleanTitle) ?: return emptyList()
        return variants(pair.first, pair.second, album = null) +
            TrackQuery(pair.second, pair.first) +
            TrackQuery(null, withoutVersionTags(cleanTitle))
    }

    /**
     * The song's name as its file name ([fileName], with its extension) gives it: cleaned like a title, after the
     * track number ([FILE_TRACK_NUMBER]), with "_" read as "&" between spaces and as a space elsewhere. Null when no
     * letter is left.
     */
    fun fileTitle(fileName: String): String? {
        val numbered = tidy(baseName(fileName))
        val base = FILE_TRACK_NUMBER.replaceFirst(numbered, "").ifBlank { numbered }.replace(LOST_AMPERSAND, "&").replace(LOST_CHARACTER, ' ')
        return cleaned(base).takeIf { LETTER.containsMatchIn(it) }
    }

    /**
     * The `q=` texts of a loose search, at most [MAX_LOOSE_QUERIES]: the title's words, then the file name's when they
     * name something else. Without version tags, feat. and punctuation: LRCLIB wants every word somewhere in the
     * title, artist or album, so an "Artist - Title" title finds the song either way.
     */
    fun looseQueries(title: String, fileName: String?): List<String> =
        listOfNotNull(cleanTitle(title), usefulFileTitle(title, fileName))
            .map(::searchWords)
            .filter { it.isNotEmpty() }
            .distinctBy(::normalize)
            .take(MAX_LOOSE_QUERIES)

    /**
     * What the song may be called, for the loose search's title check: the cleaned title and its "Artist - Title"
     * halves, then the file name's ([fileTitle]) when it names something else, and its halves. Distinct.
     */
    fun names(title: String, fileName: String?): List<String> =
        listOfNotNull(cleanTitle(title), usefulFileTitle(title, fileName))
            .flatMap { name -> listOf(name) + splitArtistTitle(name)?.toList().orEmpty() }
            .distinctBy(::normalize)

    private fun usefulFileTitle(title: String, fileName: String?): String? =
        fileName?.let(::fileTitle)?.takeIf { normalize(it) != normalize(cleanTitle(title)) }

    private fun searchWords(name: String): String {
        val bare = withoutVersionTags(name)
        val parts = splitArtistTitle(bare)?.toList() ?: listOf(bare)
        return parts.joinToString(" ") { withoutFeat(it) }.split(NOT_ALPHANUMERIC).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun baseName(fileName: String): String = fileName.substringBeforeLast('.')

    private fun variants(artist: String, title: String, album: String?): List<TrackQuery> {
        val bare = withoutVersionTags(title)
        return listOf(
            TrackQuery(artist, title, album),
            TrackQuery(artist, bare),
            TrackQuery(withoutFeat(artist), withoutFeat(bare)),
        )
    }

    /** The song's name without what downloads add to it. A title that is only junk stays as it was. */
    fun cleanTitle(raw: String): String = cleaned(raw).ifEmpty { tidy(raw) }

    /** [cleanTitle], empty for a title that is only junk. */
    private fun cleaned(raw: String): String =
        tidy(raw)
            .let(::withoutQuotes)
            .let { LEADING_TAGS.replaceFirst(it, "") }
            .let(::withoutTrackNumber) // before balancing, which would drop the ")" of "7) Title"
            .let(::balanced)
            .let(::withoutJunk)
            .let { HANDLE.replace(it, " ") }
            .let(::tidy)
            .let(::trimSeparators)

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
