package app.lyrify.models

import app.lyrify.ai.AiModels
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * One model download: where it comes from and exactly what it must be ([bytes], [sha256]). A single file is kept as
 * [name] in the models folder; an archive ([unpack]) is downloaded into the app's cache, and its files are taken out into
 * the folder [name].
 */
data class ModelSpec(val name: String, val url: String, val sha256: String, val bytes: Long, val unpack: Unpack? = null) {
    /** What to take out of an archive: the [files] in its one [folder], [bytes] in all. */
    data class Unpack(val folder: String, val files: List<String>, val bytes: Long)

    /** Bytes the model takes once in place. */
    val installedBytes: Long get() = unpack?.bytes ?: bytes

    companion object {
        /**
         * UVR-MDX-NET-Voc_FT, the vocal separation model that runs on the NPU (lyrify-bench handoff/separation-test
         * mdx-uvr_mdxnet_voc_ft.json): UVR's release on GitHub, MIT with credit to UVR.
         */
        val MDX: ModelSpec = ModelSpec(
            AiModels.MDX,
            "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Voc_FT.onnx",
            "534b2070fcc7df514b13ef660dc8cbb328679c2374d04354a5c42bb14ecce111",
            66_762_490,
        )

        /**
         * Xenova's q4 wav2vec2 export at a pinned revision, saved under the name the engine opens; on the CPU, for timing
         * known lyrics (the engine's CtcModel). It replaced the fp32 file (377,887,594 B) after the bench's model size test
         * (2026-11-04, part 1): the same 10 of 12 songs accepted at the 80% gate, 57% / 71% / 84% of their lines within
         * 0.3 / 0.5 / 1.0 s (fp32: 57% / 71% / 85%). Not the fp16 one: ONNX Runtime on arm64 runs that graph's grouped
         * Conv wrongly.
         */
        val WAV2VEC2: ModelSpec = ModelSpec(
            AiModels.WAV2VEC2,
            "https://huggingface.co/Xenova/wav2vec2-base-960h/resolve/a19f851b3d42865797e410752b4c570c871e4825/onnx/model_q4.onnx",
            "337db946188e4b3d0b4a2641dc30727a4792f70aeb1750f65a20fae471f9f217",
            89_834_049,
        )

        /** AI Hub's Whisper for [chip]: its zip, unpacked into [AiModels.WHISPER]. */
        fun whisper(chip: NpuChip): ModelSpec = ModelSpec(
            AiModels.WHISPER,
            chip.zipUrl,
            chip.zipSha256,
            chip.zipBytes,
            Unpack(chip.zipFolder, AiModels.WHISPER_FILES, chip.unpackedBytes),
        )

        /**
         * The models the AI needs on a phone with [chip], in the order they are downloaded: about 1 GB for every
         * supported chip (980,617,333 B for the tested one).
         */
        fun forChip(chip: NpuChip): List<ModelSpec> = listOf(whisper(chip), MDX, WAV2VEC2)

        /**
         * Model files an earlier version downloaded and this one no longer uses, deleted from the models folder
         * (app-private): the whisper.cpp Whisper and HT-Demucs of the CPU engine, and 2.0's earlier wav2vec2 files (fp16,
         * then fp32). The float Whisper of a chip that now gets the w8a16 one is in the same folder name as the new one:
         * it goes as an archive folder of another archive ([ModelStore.removeRetired]).
         */
        val RETIRED: List<String> = listOf(
            "ggml-large-v3-turbo-q5_0.bin",
            "htdemucs_fp16weights.onnx",
            "wav2vec2-base-960h_fp16.onnx",
            "wav2vec2-base-960h.onnx",
        )
    }
}

/** Bytes to download for [this]. */
fun List<ModelSpec>.totalBytes(): Long = sumOf { it.bytes }

/** The hosts a model download may talk to. */
interface ModelHosts {
    /** Where a download may start. */
    fun isSource(uri: URI): Boolean

    /** Where a redirect may lead. */
    fun isRedirect(uri: URI): Boolean
}

/**
 * The model hosts, and nothing else, always over https on the default port:
 * - Qualcomm AI Hub's public assets on S3 (`qaihub-public-assets.s3.us-west-2.amazonaws.com`), where release_assets.json
 *   points; it answers directly, without a redirect.
 * - GitHub releases: `github.com`, which redirects to `release-assets.githubusercontent.com` (checked with a HEAD request
 *   on 2026-11-03).
 * - Hugging Face: `huggingface.co`, which redirects to its CDN under `hf.co` (https://huggingface.co/.well-known/meta.json
 *   lists cdn-lfs*.hf.co, *.aws.cdn.hf.co, *.gcp.cdn.hf.co and transfer.xethub*.hf.co; on 2026-11-01 the wav2vec2 file
 *   came from us.aws.cdn.hf.co), so the hf.co domain as a whole is allowed for redirects.
 */
object AllowedHosts : ModelHosts {
    const val AI_HUB: String = "qaihub-public-assets.s3.us-west-2.amazonaws.com"
    const val GITHUB: String = "github.com"
    const val GITHUB_ASSETS: String = "release-assets.githubusercontent.com"
    const val HUGGING_FACE: String = "huggingface.co"

    private val SOURCES = setOf(AI_HUB, GITHUB, HUGGING_FACE)
    private val REDIRECTS = SOURCES + GITHUB_ASSETS + "hf.co"

    override fun isSource(uri: URI): Boolean = secure(uri) && host(uri) in SOURCES

    override fun isRedirect(uri: URI): Boolean = secure(uri) && host(uri).let { it in REDIRECTS || it.endsWith(".hf.co") }

    private fun secure(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) && (uri.port == -1 || uri.port == HTTPS_PORT) && uri.rawUserInfo == null

    private fun host(uri: URI): String = uri.host?.lowercase().orEmpty()

    private const val HTTPS_PORT = 443
}

/** A `Content-Range` header: `bytes first-last/total`, or `bytes * /total` (no range); unknown parts are null. */
data class ContentRange(val first: Long?, val last: Long?, val total: Long?)

/** What an HTTP response means for a model file asked for from some byte on. */
sealed interface Reply {
    /** The body is the file from byte [from] on: 0 means the whole file, and the part starts over. */
    data class Body(val from: Long) : Reply

    data object Redirect : Reply

    /** The server cannot go on from the part: the part is deleted and the file asked for whole. */
    data object StartOver : Reply

    /** Trying again will not help: the file is gone, or it is not the size it must be. */
    data class Failed(val reason: String) : Reply

    /** Worth trying again later: the server is busy, or a signed link ran out. */
    data class Busy(val reason: String) : Reply
}

/** Resuming a download with HTTP Range: the pure part. */
object RangeResume {
    private val CONTENT_RANGE = Regex("""^bytes (?:(\d+)-(\d+)|\*)/(\d+|\*)$""")
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /** The byte to ask from with [partBytes] already downloaded: the part's end, or 0 when it is larger than the file. */
    fun startAt(partBytes: Long, expected: Long): Long = if (partBytes in 0..expected) partBytes else 0

    /** The `Range` header asking for everything from [offset] on; none from the start. */
    fun header(offset: Long): String? = if (offset > 0) "bytes=$offset-" else null

    fun contentRange(header: String?): ContentRange? {
        val match = header?.trim()?.let(CONTENT_RANGE::matchEntire) ?: return null
        val (first, last, total) = match.destructured
        if (first.isEmpty()) return ContentRange(null, null, total.toLongOrNull())
        val range = ContentRange(first.toLong(), last.toLong(), total.toLongOrNull())
        return range.takeIf { last.toLong() >= first.toLong() }
    }

    /** The response ([code], its `Content-Range` and length) to a request from [offset] for a file of [expected] bytes. */
    fun reply(code: Int, contentRange: String?, contentLength: Long, offset: Long, expected: Long): Reply = when (code) {
        HttpURLConnection.HTTP_OK ->
            if (contentLength >= 0 && contentLength != expected) Reply.Failed("$contentLength bytes instead of $expected") else Reply.Body(0)
        HttpURLConnection.HTTP_PARTIAL -> partial(contentRange(contentRange), offset, expected)
        in REDIRECTS -> Reply.Redirect
        HTTP_RANGE_NOT_SATISFIABLE -> Reply.StartOver
        HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE -> Reply.Failed("HTTP $code")
        else -> Reply.Busy("HTTP $code")
    }

    private fun partial(range: ContentRange?, offset: Long, expected: Long): Reply {
        val first = range?.first ?: return Reply.Busy("206 without a usable Content-Range")
        return when {
            range.total != null && range.total != expected -> Reply.Failed("${range.total} bytes instead of $expected")
            first != offset || range.last != expected - 1 -> Reply.StartOver
            else -> Reply.Body(offset)
        }
    }
}

/**
 * The app-private models folder (`filesDir/models`). A single-file model is in place under its own name only once its
 * SHA-256 matched: it is downloaded into `<name>.part` and moved there after the check, so a file of the right size under
 * its own name is a verified one. An archive is downloaded into `<cache>/<name>.zip.part`; once its SHA-256 matched, its
 * files are taken out into the folder `<name>`, each through a `.part` file, and their sizes and SHA-256 are recorded in
 * the folder's [MANIFEST], written last; then the zip is deleted. So the folder is in place when its manifest is the
 * archive's and lists each file at the size it has.
 */
class ModelStore(
    val folder: File,
    val specs: List<ModelSpec>,
    /** Where archives are downloaded: app-private cache. */
    val cache: File,
) {
    val totalBytes: Long get() = specs.totalBytes()

    /** A file model, or the folder of an archive's files. */
    fun file(spec: ModelSpec): File = File(folder, spec.name)

    /** Where [spec] is downloaded to. */
    fun part(spec: ModelSpec): File = if (spec.unpack == null) File(folder, spec.name + PART) else File(cache, spec.name + ZIP + PART)

    /** [spec] is in place: verified before it was moved there, or unpacked from a verified archive (see the class). */
    fun has(spec: ModelSpec): Boolean {
        val unpack = spec.unpack ?: return file(spec).length() == spec.bytes
        val manifest = manifest(spec) ?: return false
        val dir = file(spec)
        return manifest.archiveSha256 == spec.sha256 && manifest.files.keys == unpack.files.toSet() &&
            manifest.files.all { (name, entry) -> File(dir, name).length() == entry.bytes }
    }

    /** What an archive's [MANIFEST] records, or null when there is none or it cannot be read. */
    fun manifest(spec: ModelSpec): Manifest? = try {
        File(file(spec), MANIFEST).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(Manifest.serializer(), it) }
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    }

    /** Every model is in place. */
    fun isReady(): Boolean = specs.all(::has)

    /** The engine's model files, once every one is in place. */
    fun models(): AiModels? = if (isReady()) AiModels.inFolder(folder) else null

    /** Bytes downloaded so far: models in place (their download size), plus parts. */
    fun bytesOnDisk(): Long = specs.sumOf { if (has(it)) it.bytes else part(it).length().coerceAtMost(it.bytes) }

    /**
     * Free space the download still needs at its fullest, plus [SPARE_BYTES]. The models come one after the other in
     * [specs] order, and an archive's zip and its files are on disk together until the zip is deleted once unpacked; so
     * the most the disk holds beyond today is, at some model, what the ones before it added plus its own rest (and an
     * archive's files). What a model not in place already has under its name (a folder whose unpacking was cut short)
     * is credited: the download deletes it before it fetches or unpacks that model. Another archive's folder is not: it
     * is retired, and counted with [retiredBytes]. For the tested chip from nothing: its zip and its files, about
     * 1.8 GB, plus the spare.
     */
    fun bytesNeeded(): Long {
        val left = specs.filterNot(::has)
        if (left.isEmpty()) return 0
        val stale = staleFolders().toSet()
        var added = 0L
        var peak = 0L
        for (spec in left) {
            val onDisk = part(spec).length().coerceIn(0, spec.bytes)
            val unpacked = spec.unpack?.bytes ?: 0
            val replaced = file(spec).takeUnless { it in stale }?.let(::bytesIn) ?: 0
            peak = maxOf(peak, added - replaced + spec.bytes - onDisk + unpacked)
            // In place: a file adds the rest of its bytes; an archive adds its files, and its zip (the part) is deleted.
            added += (if (spec.unpack == null) spec.bytes - onDisk else unpacked - onDisk) - replaced
        }
        return peak + SPARE_BYTES
    }

    /**
     * Deletes what an earlier version or run left and nothing uses, nothing else; true when none of it is left: the
     * [ModelSpec.RETIRED] files with their parts; an archive's folder unpacked from another archive than the pinned one
     * (the float Whisper of a chip that now gets the w8a16 one, or any earlier pin); and an archive's verified zip still
     * in the cache once its folder is in place (the app died after writing the manifest, or the zip could not be
     * deleted: about 0.8 GB for Whisper). A folder without a manifest is left alone, and so is its zip: it may be one
     * being unpacked, and the download replaces it anyway.
     */
    fun removeRetired(): Boolean = ((staleFolders() + leftoverZips()).map(::remove) + removeRetired(folder)).all { it }

    /** Bytes [removeRetired] would free: the download deletes them before it checks the room. */
    fun retiredBytes(): Long = (retiredFiles(folder) + staleFolders() + leftoverZips()).sumOf(::bytesIn)

    /** Archive folders whose manifest names another archive than the one pinned now. */
    private fun staleFolders(): List<File> =
        specs.filter { spec -> spec.unpack != null && manifest(spec)?.let { it.archiveSha256 != spec.sha256 } == true }.map(::file)

    /** Archives' zips in the cache although their folder is in place: nothing reads them any more. */
    private fun leftoverZips(): List<File> = specs.filter { it.unpack != null && has(it) }.map(::part).filter { it.exists() }

    /** Bytes in [file], or in the folder [file]; 0 when it is not there. */
    private fun bytesIn(file: File): Long =
        if (file.isDirectory) file.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else file.length()

    /**
     * Deletes the models, their parts and an archive's folder of files, and models an earlier version used, nothing
     * else; true when none is left.
     */
    fun delete(): Boolean =
        (specs.map { remove(file(it)) && remove(part(it)) } + removeRetired(folder)).all { it }

    /**
     * Deletes [model], one of this folder's models that would not load (a file, or an archive's folder), so the models
     * are not ready and the download fetches it again. Anything else is left alone (false). True when it is gone.
     */
    fun discard(model: File): Boolean {
        val file = specs.map(::file).firstOrNull { it == model } ?: return false
        return remove(file)
    }

    /** One file of an archive, as recorded when it was taken out. */
    @Serializable
    data class Entry(val bytes: Long, val sha256: String)

    /** An archive's folder: the archive it came from and the files taken out of it. */
    @Serializable
    data class Manifest(val archiveSha256: String, val files: Map<String, Entry>)

    companion object {
        const val PART = ".part"
        const val ZIP = ".zip"
        const val MANIFEST = "manifest.json"

        /** Room to leave free beyond the models themselves. */
        const val SPARE_BYTES: Long = 200L * 1024 * 1024

        internal val json = Json { ignoreUnknownKeys = true }

        /** Deletes the models an earlier version used ([ModelSpec.RETIRED]) and their parts, nothing else; true when none is left. */
        fun removeRetired(folder: File): Boolean = retiredFiles(folder).map(::remove).all { it }

        private fun retiredFiles(folder: File): List<File> = ModelSpec.RETIRED.flatMap { listOf(File(folder, it), File(folder, it + PART)) }

        /** Deletes [file], or the folder [file] with what it holds (only the models' own folders get here). */
        private fun remove(file: File): Boolean = !file.exists() || (if (file.isDirectory) file.deleteRecursively() else file.delete())
    }
}

/** How a download run ended. */
sealed interface Download {
    data object Done : Download

    /** Worth trying again later: the network, a busy server, no room yet. What was downloaded stays. */
    data class Interrupted(val reason: String) : Download

    /** Will not get better by itself: a file that is not the pinned one (its part is deleted), a link gone or off Hugging Face. */
    data class Failed(val reason: String) : Download
}

/**
 * [attempt] again at once while it is interrupted after getting further ([bytes] on disk grew): on a slow or flaky
 * network a dropped connection goes on from its part right away instead of waiting out a growing backoff. An attempt
 * that got nowhere (no network, a busy server, no room) ends it.
 */
suspend fun retryWhileProgressing(bytes: () -> Long, attempt: suspend () -> Download): Download {
    while (true) {
        val before = bytes()
        val result = attempt()
        if (result !is Download.Interrupted || bytes() <= before) return result
    }
}

/**
 * Downloads the models [store] lacks, one after the other, each into its `.part` file from where it stopped (HTTP
 * Range), then checks its SHA-256 and only then moves it into place, or for an archive takes its files out ([unpack]);
 * a mismatch deletes the part. Talks only to [hosts]; redirects are followed by hand so each one is checked first.
 * Blocking IO runs on [io]; a cancelled run disconnects at once and keeps its part.
 *
 * An archive is not unpacked while it streams in: a dropped connection resumes the zip with a Range request, and the
 * SHA-256 of the whole zip can only be checked once it is all on disk.
 */
class ModelDownloader(
    private val store: ModelStore,
    private val userAgent: String,
    private val hosts: ModelHosts = AllowedHosts,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Free bytes where the models go; tests pass a fixed number. */
    private val freeBytes: (File) -> Long = File::getUsableSpace,
    /** Makes the connection for a URL without connecting yet. */
    private val open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val log: (String) -> Unit = {},
) {
    /** Downloads every model not in place; [progress] hears the bytes done of all of them, at most once per whole percent. */
    suspend fun downloadAll(progress: suspend (done: Long, total: Long) -> Unit): Download = withContext(io) {
        store.folder.mkdirs()
        store.cache.mkdirs()
        // Their room counts for the new ones.
        if (!store.removeRetired()) log("a model an earlier version used could not be deleted")
        val needed = store.bytesNeeded()
        if (needed > 0 && freeBytes(store.folder) < needed) {
            return@withContext Download.Interrupted("not enough free space: $needed bytes needed")
        }
        val total = store.totalBytes
        val meter = Meter(total, progress)
        meter.at(store.bytesOnDisk(), force = true)
        for (spec in store.specs) {
            if (store.has(spec)) continue
            val others = store.bytesOnDisk() - store.part(spec).length().coerceAtMost(spec.bytes)
            val result = download(spec) { meter.at(others + it) }
            if (result != Download.Done) return@withContext result
            log("${spec.name}: downloaded and verified")
        }
        meter.at(total, force = true)
        Download.Done
    }

    private suspend fun download(spec: ModelSpec, onBytes: suspend (Long) -> Unit): Download {
        val file = store.file(spec)
        val part = store.part(spec)
        // Not in place: not one this downloader moved there whole (a file), or not all of it unpacked (a folder).
        if (file.exists() && !(if (file.isDirectory) file.deleteRecursively() else file.delete())) {
            return Download.Interrupted("${spec.name}: an old copy could not be deleted")
        }
        val source = try {
            URI(spec.url)
        } catch (_: URISyntaxException) {
            return Download.Failed("${spec.name}: not a valid address")
        }
        if (!hosts.isSource(source)) return Download.Failed("${spec.name}: not an allowed address")
        var startedOver = false
        while (true) {
            val offset = RangeResume.startAt(part.length(), spec.bytes)
            if (offset == 0L && part.exists() && !part.delete()) return Download.Interrupted("${spec.name}: part could not be reset")
            if (offset == spec.bytes) return finish(spec, part, file)
            when (val fetched = fetch(source, spec, offset, part, onBytes)) {
                Fetch.Complete -> return finish(spec, part, file)
                Fetch.StartOver -> {
                    if (startedOver || !part.delete()) return Download.Interrupted("${spec.name}: the server cannot go on from $offset")
                    startedOver = true
                }
                is Fetch.Stop -> return fetched.download
            }
        }
    }

    private sealed interface Fetch {
        data object Complete : Fetch

        data object StartOver : Fetch

        data class Stop(val download: Download) : Fetch
    }

    /** One request for [spec] from [offset], redirects followed; the body goes into [part]. Cancelling disconnects at once. */
    private suspend fun fetch(source: URI, spec: ModelSpec, offset: Long, part: File, onBytes: suspend (Long) -> Unit): Fetch = coroutineScope {
        val current = AtomicReference<HttpURLConnection?>(null)
        val exchange = async(io) { follow(source, spec, offset, part, current, onBytes) }
        try {
            exchange.await()
        } finally {
            // Also on cancellation: a blocked read does not notice it, but a disconnect ends it from any thread.
            current.get()?.disconnect()
        }
    }

    private suspend fun follow(
        source: URI,
        spec: ModelSpec,
        offset: Long,
        part: File,
        current: AtomicReference<HttpURLConnection?>,
        onBytes: suspend (Long) -> Unit,
    ): Fetch {
        var uri = source
        for (hop in 0..MAX_REDIRECTS) {
            val connection = try {
                open(uri).also(current::set)
            } catch (e: IOException) {
                return Fetch.Stop(Download.Interrupted("${spec.name}: ${e.javaClass.simpleName}: ${e.message}"))
            }
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                // Without it Android asks for gzip and unpacks it, and byte ranges and lengths stop matching the file.
                connection.setRequestProperty("Accept-Encoding", "identity")
                RangeResume.header(offset)?.let { connection.setRequestProperty("Range", it) }
                val code = connection.responseCode
                val reply = RangeResume.reply(code, connection.getHeaderField("Content-Range"), connection.contentLengthLong, offset, spec.bytes)
                when (reply) {
                    Reply.Redirect -> {
                        val next = connection.getHeaderField("Location")?.let { location -> resolve(uri, location) }
                        if (next == null || !hosts.isRedirect(next)) {
                            return Fetch.Stop(Download.Failed("${spec.name}: redirect to ${next?.host ?: "nowhere"} refused"))
                        }
                        uri = next
                    }
                    is Reply.Body -> return receive(connection, spec, reply.from, part, onBytes)
                    Reply.StartOver -> return Fetch.StartOver
                    is Reply.Failed -> {
                        part.delete()
                        return Fetch.Stop(Download.Failed("${spec.name}: ${reply.reason}"))
                    }
                    is Reply.Busy -> return Fetch.Stop(Download.Interrupted("${spec.name}: ${reply.reason}"))
                }
            } catch (e: IOException) {
                return Fetch.Stop(Download.Interrupted("${spec.name}: ${e.javaClass.simpleName}: ${e.message}"))
            } finally {
                connection.disconnect()
            }
        }
        return Fetch.Stop(Download.Failed("${spec.name}: more than $MAX_REDIRECTS redirects"))
    }

    private fun resolve(base: URI, location: String): URI? = try {
        base.resolve(location)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** The body, appended to [part] (which holds [from] bytes; from 0 it starts over). Never writes past the file's size. */
    private suspend fun receive(connection: HttpURLConnection, spec: ModelSpec, from: Long, part: File, onBytes: suspend (Long) -> Unit): Fetch {
        var have = from
        FileOutputStream(part, from > 0).use { out ->
            try {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (have + read > spec.bytes) {
                            out.close()
                            part.delete()
                            return Fetch.Stop(Download.Failed("${spec.name}: more than ${spec.bytes} bytes"))
                        }
                        out.write(buffer, 0, read)
                        have += read
                        onBytes(have)
                    }
                }
            } finally {
                // What arrived stays for the next run, synced so the part's length is what is on disk.
                if (part.exists()) out.fd.sync()
            }
        }
        return if (have == spec.bytes) Fetch.Complete else Fetch.Stop(Download.Interrupted("${spec.name}: connection closed at $have of ${spec.bytes} bytes"))
    }

    /** Checks the whole part's SHA-256, then moves it into place or unpacks it; a mismatch deletes it. */
    private suspend fun finish(spec: ModelSpec, part: File, file: File): Download {
        val hash = try {
            sha256(part)
        } catch (e: IOException) {
            return Download.Interrupted("${spec.name}: not readable: ${e.javaClass.simpleName}")
        }
        if (hash != spec.sha256) {
            part.delete()
            return Download.Failed("${spec.name}: checksum mismatch, download deleted")
        }
        spec.unpack?.let { return unpack(spec, it, part, file) }
        return try {
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Download.Done
        } catch (e: IOException) {
            Download.Interrupted("${spec.name}: not moved into place: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Takes [unpack]'s files out of the verified [zip] into [folder]: each into `<file>.part`, its size and SHA-256 taken
     * as it is written (the zip's CRC-32 is checked too), then moved into place; the manifest last; then the zip goes.
     * Only the listed names are written, so nothing in the archive can land elsewhere. A full disk keeps the zip for the
     * next run; an archive without the expected files fails.
     */
    private suspend fun unpack(spec: ModelSpec, unpack: ModelSpec.Unpack, zip: File, folder: File): Download {
        val wanted = unpack.files.toSet()
        val files = linkedMapOf<String, ModelStore.Entry>()
        try {
            folder.mkdirs()
            ZipInputStream(BufferedInputStream(zip.inputStream(), HASH_BUFFER_BYTES)).use { input ->
                var written = 0L
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name.removePrefix(unpack.folder + "/")
                    if (entry.isDirectory || entry.name != unpack.folder + "/" + name || name !in wanted || name in files) continue
                    val target = File(folder, name)
                    val part = File(folder, name + ModelStore.PART)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    FileOutputStream(part).use { out ->
                        val buffer = ByteArray(HASH_BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            bytes += read
                            written += read
                            if (written > unpack.bytes) throw ZipException("more than ${unpack.bytes} bytes unpacked")
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                        out.fd.sync()
                    }
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    files[name] = ModelStore.Entry(bytes, hex(digest.digest()))
                }
            }
            if (files.keys != wanted) {
                folder.deleteRecursively()
                zip.delete()
                return Download.Failed("${spec.name}: the archive lacks ${(wanted - files.keys).sorted()}")
            }
            val manifest = File(folder, ModelStore.MANIFEST)
            val aside = File(folder, ModelStore.MANIFEST + ModelStore.PART)
            aside.writeText(ModelStore.json.encodeToString(ModelStore.Manifest.serializer(), ModelStore.Manifest(spec.sha256, files)))
            Files.move(aside.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: ZipException) {
            // The zip matched its SHA-256, so this is not a damaged download: the archive is not what was pinned.
            folder.deleteRecursively()
            zip.delete()
            return Download.Failed("${spec.name}: not a usable archive: ${e.message}")
        } catch (e: IOException) {
            return Download.Interrupted("${spec.name}: not unpacked: ${e.javaClass.simpleName}")
        }
        if (!zip.delete()) log("${spec.name}: the downloaded archive could not be deleted")
        log("${spec.name}: unpacked ${files.size} files, ${files.values.sumOf { it.bytes }} bytes")
        return Download.Done
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Reports progress at most once per whole percent, and when [force]d. */
    private class Meter(private val total: Long, private val report: suspend (Long, Long) -> Unit) {
        private var shown = -1L

        suspend fun at(done: Long, force: Boolean = false) {
            val percent = if (total > 0) done * PERCENT / total else PERCENT
            if (force || percent > shown) {
                shown = percent
                report(done, total)
            }
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val BUFFER_BYTES = 64 * 1024
        const val HASH_BUFFER_BYTES = 1024 * 1024
        const val PERCENT = 100L
    }
}

/** What the AI card says about the models. */
sealed interface ModelState {
    val totalBytes: Long

    /** Not downloaded, or only part of them ([doneBytes]) by a download that stopped. */
    data class Missing(val doneBytes: Long, override val totalBytes: Long) : ModelState

    /** A download is under way, or [waiting] for its turn (Wi-Fi, a retry). */
    data class Downloading(val doneBytes: Long, override val totalBytes: Long, val waiting: Boolean) : ModelState {
        val percent: Int get() = (doneBytes * 100 / totalBytes.coerceAtLeast(1)).toInt()
    }

    /** Not all in place, and the phone has less free space than the rest needs ([neededBytes], [ModelStore.bytesNeeded]). */
    data class NoRoom(val doneBytes: Long, override val totalBytes: Long, val neededBytes: Long) : ModelState

    data class Ready(override val totalBytes: Long) : ModelState

    /** The last download failed for good (a file not as pinned); trying again starts it anew. */
    data class Failed(val doneBytes: Long, override val totalBytes: Long) : ModelState
}

/** Where the download work stands, from WorkManager. */
enum class DownloadPhase { WAITING, RUNNING, FAILED }

/**
 * The card's model state from the files on disk ([ready], [onDisk] bytes), the download work's [phase] and reported
 * [doneBytes], and the free space ([freeBytes]) against what the rest of the download needs ([neededBytes]).
 */
fun modelState(ready: Boolean, onDisk: Long, total: Long, phase: DownloadPhase?, doneBytes: Long?, neededBytes: Long = 0, freeBytes: Long = Long.MAX_VALUE): ModelState = when {
    ready -> ModelState.Ready(total)
    phase == DownloadPhase.RUNNING -> ModelState.Downloading(doneBytes ?: onDisk, total, waiting = false)
    phase == DownloadPhase.FAILED -> ModelState.Failed(onDisk, total)
    freeBytes < neededBytes -> ModelState.NoRoom(onDisk, total, neededBytes)
    phase == DownloadPhase.WAITING -> ModelState.Downloading(onDisk, total, waiting = true)
    else -> ModelState.Missing(onDisk, total)
}
