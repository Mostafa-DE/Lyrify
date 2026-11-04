package app.lyrify.models

import app.lyrify.ai.AiModels
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Collections

class ModelStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    // The pinned models

    @Test
    fun `the models are AI Hub's Whisper for the chip, unpacked, UVR's MDX from GitHub and wav2vec2 from Hugging Face`() {
        val chip = NpuChips.ALL.first()
        assertEquals(
            listOf(
                ModelSpec(
                    AiModels.WHISPER,
                    chip.zipUrl,
                    "12c4469b443b06ac6ef36b4268f7822cda798c03abadcda0c93f7b9ddbb24f16",
                    824_020_794,
                    ModelSpec.Unpack(
                        "whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy",
                        listOf("encoder.onnx", "encoder.bin", "decoder.onnx", "decoder.bin", "metadata.json"),
                        983_166_696,
                    ),
                ),
                ModelSpec(
                    "UVR-MDX-NET-Voc_FT.onnx",
                    "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Voc_FT.onnx",
                    "534b2070fcc7df514b13ef660dc8cbb328679c2374d04354a5c42bb14ecce111",
                    66_762_490,
                ),
                // q4 at the pinned revision: validated in the bench's model size test against the fp32 file it replaces.
                ModelSpec(
                    "wav2vec2-base-960h_q4.onnx",
                    "https://huggingface.co/Xenova/wav2vec2-base-960h/resolve/a19f851b3d42865797e410752b4c570c871e4825/onnx/model_q4.onnx",
                    "337db946188e4b3d0b4a2641dc30727a4792f70aeb1750f65a20fae471f9f217",
                    89_834_049,
                ),
            ),
            ModelSpec.forChip(chip),
        )
        // The download the card shows for the tested chip: about 1 GB (0.98 GB), not 2.46 GB as with float and fp32.
        assertEquals(980_617_333L, ModelSpec.forChip(chip).totalBytes())
        // Every supported chip's set is about 1 GB, each with its own Whisper zip.
        NpuChips.ALL.forEach { assertTrue(it.aiHub, ModelSpec.forChip(it).totalBytes() in 975_000_000L..985_000_000L) }
        assertEquals(
            listOf("ggml-large-v3-turbo-q5_0.bin", "htdemucs_fp16weights.onnx", "wav2vec2-base-960h_fp16.onnx", "wav2vec2-base-960h.onnx"),
            ModelSpec.RETIRED,
        )
        assertTrue(ModelSpec.forChip(chip).none { it.name in ModelSpec.RETIRED })
        NpuChips.ALL.flatMap(ModelSpec::forChip).forEach { assertTrue(it.url, AllowedHosts.isSource(URI(it.url))) }
    }

    @Test
    fun `a download starts only at AI Hub's bucket, GitHub or Hugging Face, over https on the default port`() {
        listOf(
            "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/a.zip",
            "https://github.com/TRvlvr/model_repo/releases/download/x/a.onnx",
            "https://huggingface.co/a/b/resolve/main/c.bin",
            "https://HuggingFace.co/a",
        ).forEach { assertTrue(it, AllowedHosts.isSource(URI(it))) }
        listOf(
            "http://huggingface.co/a",
            "https://huggingface.co:8443/a",
            "https://huggingface.co.example.com/a",
            "https://us.aws.cdn.hf.co/a",
            "https://release-assets.githubusercontent.com/a",
            "https://objects.githubusercontent.com/a",
            "https://raw.githubusercontent.com/a",
            "https://gist.github.com/a",
            "https://s3.us-west-2.amazonaws.com/qaihub-public-assets/a.zip",
            "https://other-bucket.s3.us-west-2.amazonaws.com/a.zip",
            "https://huggingface.co@example.com/a",
        ).forEach { assertFalse(it, AllowedHosts.isSource(URI(it))) }
    }

    @Test
    fun `redirects are followed to GitHub's release assets, Hugging Face and its CDN, and the sources only`() {
        listOf(
            "https://release-assets.githubusercontent.com/github-production-release-asset/514182217/x?sp=r&sig=y",
            "https://us.aws.cdn.hf.co/xet-bridge-us/a?X-Amz-Signature=b",
            "https://cdn-lfs.hf.co/repos/a",
            "https://cas-bridge.xethub.hf.co/a",
            "https://huggingface.co/a/resolve/main/b",
            "https://hf.co/a",
            "https://github.com/a",
            "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/a.zip",
        ).forEach { assertTrue(it, AllowedHosts.isRedirect(URI(it))) }
        listOf(
            "http://us.aws.cdn.hf.co/a",
            "https://example.com/a",
            "https://hf.co.example.com/a",
            "https://examplehf.co/a",
            "https://us.aws.cdn.hf.co:444/a",
            "https://objects.githubusercontent.com/a",
            "https://release-assets.githubusercontent.com.example.com/a",
            "ftp://cdn-lfs.hf.co/a",
            "/relative/but/unresolved",
        ).forEach { assertFalse(it, AllowedHosts.isRedirect(URI(it))) }
    }

    // Resuming

    @Test
    fun `a part is asked for from where it stopped, a part too large starts over`() {
        assertEquals(0L, RangeResume.startAt(partBytes = 0, expected = 100))
        assertEquals(40L, RangeResume.startAt(partBytes = 40, expected = 100))
        assertEquals(100L, RangeResume.startAt(partBytes = 100, expected = 100))
        assertEquals(0L, RangeResume.startAt(partBytes = 140, expected = 100))
        assertNull(RangeResume.header(0))
        assertEquals("bytes=40-", RangeResume.header(40))
    }

    @Test
    fun `content ranges are read`() {
        assertEquals(ContentRange(40, 99, 100), RangeResume.contentRange("bytes 40-99/100"))
        assertEquals(ContentRange(40, 99, null), RangeResume.contentRange("bytes 40-99/*"))
        assertEquals(ContentRange(null, null, 100), RangeResume.contentRange("bytes */100"))
        assertNull(RangeResume.contentRange(null))
        assertNull(RangeResume.contentRange("items 1-2/3"))
        assertNull(RangeResume.contentRange("bytes 99-40/100"))
    }

    @Test
    fun `replies to a ranged request`() {
        assertEquals(Reply.Body(from = 40), RangeResume.reply(206, "bytes 40-99/100", 60, offset = 40, expected = 100))
        assertEquals(Reply.Body(from = 0), RangeResume.reply(200, null, 100, offset = 40, expected = 100))
        assertEquals(Reply.Body(from = 0), RangeResume.reply(200, null, -1, offset = 0, expected = 100))
        assertEquals(Reply.StartOver, RangeResume.reply(206, "bytes 0-99/100", 100, offset = 40, expected = 100))
        assertEquals(Reply.StartOver, RangeResume.reply(416, "bytes */100", 0, offset = 40, expected = 100))
        assertTrue(RangeResume.reply(206, "bytes 40-99/120", 60, offset = 40, expected = 100) is Reply.Failed)
        assertTrue(RangeResume.reply(206, null, 60, offset = 40, expected = 100) is Reply.Busy)
        assertTrue(RangeResume.reply(200, null, 120, offset = 0, expected = 100) is Reply.Failed)
        listOf(301, 302, 303, 307, 308).forEach { assertEquals(Reply.Redirect, RangeResume.reply(it, null, 0, 0, 100)) }
        listOf(404, 410).forEach { assertTrue(RangeResume.reply(it, null, 0, 0, 100) is Reply.Failed) }
        listOf(403, 429, 500, 503).forEach { assertTrue(RangeResume.reply(it, null, 0, 0, 100) is Reply.Busy) }
    }

    // The store

    private fun bytes(size: Int, seed: Int): ByteArray = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val first = bytes(300_000, 1)
    private val second = bytes(120_000, 7)

    private fun specs(base: String) = listOf(
        ModelSpec("first.bin", "$base/repo/first.bin", sha256(first), first.size.toLong()),
        ModelSpec("second.onnx", "$base/repo/second.onnx", sha256(second), second.size.toLong()),
    )

    private val models: File get() = File(folder.root, "models")
    private val cache: File get() = File(folder.root, "cache")

    private fun store(base: String = "http://127.0.0.1:1", list: List<ModelSpec> = specs(base)) = ModelStore(models, list, cache)

    @Test
    fun `ready only when every model file is in place with its size`() {
        val store = store()
        assertFalse(store.isReady())
        models.mkdirs()
        File(models, "first.bin").writeBytes(first)
        File(models, "second.onnx.part").writeBytes(second.copyOf(1_000))
        assertFalse(store.isReady())
        assertEquals(301_000L, store.bytesOnDisk())
        File(models, "second.onnx").writeBytes(second.copyOf(119_999))
        assertFalse(store.isReady())
        File(models, "second.onnx").writeBytes(second)
        assertTrue(store.isReady())
        assertEquals(420_000L, store.totalBytes)
    }

    @Test
    fun `an archive's folder is in place when its manifest is the archive's and lists each file at its size`() {
        val spec = archiveSpec("http://127.0.0.1:1")
        val store = store(list = listOf(spec))
        assertFalse(store.has(spec))
        val dir = File(models, "pack").apply { mkdirs() }
        packed.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        assertFalse(store.has(spec)) // no manifest: not unpacked to the end
        val entries = packed.mapValues { (_, bytes) -> ModelStore.Entry(bytes.size.toLong(), sha256(bytes)) }
        File(dir, ModelStore.MANIFEST).writeText(ModelStore.json.encodeToString(ModelStore.Manifest.serializer(), ModelStore.Manifest(spec.sha256, entries)))
        assertTrue(store.has(spec))
        assertEquals(AiModels.inFolder(models).copy(whisper = dir), AiModels(dir, File(models, AiModels.MDX), File(models, AiModels.WAV2VEC2)))
        File(dir, "b.bin").writeBytes(ByteArray(3))
        assertFalse(store.has(spec)) // a file of another size
        File(dir, "b.bin").writeBytes(packed.getValue("b.bin"))
        File(dir, ModelStore.MANIFEST).writeText(ModelStore.json.encodeToString(ModelStore.Manifest.serializer(), ModelStore.Manifest("other", entries)))
        assertFalse(store.has(spec)) // another archive's
    }

    @Test
    fun `delete removes the models and parts, and nothing else`() {
        models.mkdirs()
        File(models, "first.bin").writeBytes(first)
        File(models, "second.onnx.part").writeBytes(second.copyOf(10))
        val other = File(folder.root, "ledger.json").apply { writeText("{}") }

        assertTrue(store().delete())

        assertFalse(File(models, "first.bin").exists())
        assertFalse(File(models, "second.onnx.part").exists())
        assertTrue(other.exists())
    }

    @Test
    fun `models an earlier version used are deleted, with their parts, and nothing else`() {
        models.mkdirs()
        File(models, "first.bin").writeBytes(first)
        File(models, OLD).writeBytes(second)
        File(models, "$OLD.part").writeBytes(second.copyOf(10))
        File(models, "htdemucs_fp16weights.onnx").writeBytes(second)
        val other = File(models, "notes.txt").apply { writeText("kept") }

        assertTrue(ModelStore.removeRetired(models))

        assertEquals(setOf("first.bin", "notes.txt"), models.list().orEmpty().toSet())
        assertTrue(other.exists())
        assertEquals(300_000L, store().bytesOnDisk())
    }

    @Test
    fun `an archive's folder from another archive is retired, the pinned one and one being unpacked are not`() {
        val spec = archiveSpec("http://127.0.0.1:1")
        val store = store(list = specs("http://127.0.0.1:1") + spec)
        val dir = File(models, "pack").apply { mkdirs() }
        File(dir, "a.onnx").writeBytes(bytes(1_000, 3))
        File(dir, "b.bin").writeBytes(bytes(2_000, 5))
        val old = mapOf("a.onnx" to ModelStore.Entry(1_000, "x"), "b.bin" to ModelStore.Entry(2_000, "y"))
        File(dir, ModelStore.MANIFEST).writeText(ModelStore.json.encodeToString(ModelStore.Manifest.serializer(), ModelStore.Manifest("float zip", old)))
        // As on a phone with 2.0's float Whisper and fp32 wav2vec2 when the w8a16 set comes: both go before the download.
        File(models, "wav2vec2-base-960h.onnx").writeBytes(bytes(500, 9))
        File(models, "first.bin").writeBytes(first)
        assertFalse(store.has(spec))
        assertEquals(1_000L + 2_000 + File(dir, ModelStore.MANIFEST).length() + 500, store.retiredBytes())

        assertTrue(store.removeRetired())

        assertFalse(dir.exists())
        assertFalse(File(models, "wav2vec2-base-960h.onnx").exists())
        assertTrue(File(models, "first.bin").exists())
        assertEquals(0L, store.retiredBytes())

        // Being unpacked (no manifest yet), or the pinned archive's: kept.
        dir.mkdirs()
        File(dir, "a.onnx").writeBytes(bytes(1_000, 3))
        assertTrue(store.removeRetired())
        assertTrue(File(dir, "a.onnx").exists())
        val entries = packed.mapValues { (_, b) -> ModelStore.Entry(b.size.toLong(), sha256(b)) }
        packed.forEach { (name, b) -> File(dir, name).writeBytes(b) }
        File(dir, ModelStore.MANIFEST).writeText(ModelStore.json.encodeToString(ModelStore.Manifest.serializer(), ModelStore.Manifest(spec.sha256, entries)))
        assertTrue(store.has(spec))
        assertEquals(0L, store.retiredBytes())
        assertTrue(store.removeRetired())
        assertTrue(store.has(spec))
    }

    @Test
    fun `a download deletes the old Whisper first, so its room counts for the new one`() {
        val base = start()
        files["/pack.zip"] = archive
        val spec = archiveSpec(base)
        val dir = File(models, "pack").apply { mkdirs() }
        File(dir, "a.onnx").writeBytes(bytes(5_000_000, 3))
        File(dir, ModelStore.MANIFEST).writeText(
            ModelStore.json.encodeToString(ModelStore.Manifest.serializer(), ModelStore.Manifest("float zip", mapOf("a.onnx" to ModelStore.Entry(5_000_000, "x")))),
        )
        val needed = store(base, listOf(spec)).bytesNeeded()
        // Another archive's folder is not credited here: it is retired, and the card counts it as free (retiredBytes).
        assertEquals(archive.size + packed.values.sumOf { it.size.toLong() } + ModelStore.SPARE_BYTES, needed)
        // The phone's free space: short of what the new one needs while the old folder is there.
        val free = { _: File -> if (dir.exists()) needed - 1 else needed }
        val download = runBlocking {
            ModelDownloader(store(base, listOf(spec)), "Lyrify/test", loopback, Dispatchers.IO, freeBytes = free).downloadAll { _, _ -> }
        }

        assertEquals(Download.Done, download)
        assertTrue(store(base, listOf(spec)).isReady())
        assertArrayEquals(packed.getValue("a.onnx"), File(dir, "a.onnx").readBytes())
    }

    @Test
    fun `a half-unpacked folder is credited, since the download deletes it before it unpacks again`() {
        val base = start()
        files["/pack.zip"] = archive
        val spec = archiveSpec(base)
        cache.mkdirs()
        // The zip is all there and verified; unpacking it was cut short (no manifest yet).
        File(cache, "pack.zip.part").writeBytes(archive)
        val dir = File(models, "pack").apply { mkdirs() }
        File(dir, "a.onnx").writeBytes(packed.getValue("a.onnx"))
        File(dir, "b.bin.part").writeBytes(packed.getValue("b.bin").copyOf(1_500_000))
        val unpacked = packed.values.sumOf { it.size.toLong() }
        val needed = unpacked - 1_000 - 1_500_000 + ModelStore.SPARE_BYTES
        assertEquals(needed, store(base, listOf(spec)).bytesNeeded())

        // With just that much free the download goes on: the half folder goes, the zip is unpacked again, not fetched.
        assertTrue(download(base, free = needed - 1, list = listOf(spec)) is Download.Interrupted)
        assertEquals(Download.Done, download(base, free = needed, list = listOf(spec)))
        assertEquals(emptyList<String>(), requests)
        assertTrue(store(base, listOf(spec)).isReady())
        assertEquals(setOf("a.onnx", "b.bin", ModelStore.MANIFEST), dir.list().orEmpty().toSet())
    }

    @Test
    fun `a verified zip left in the cache once its folder is in place is deleted, one still needed is not`() {
        val spec = archiveSpec("http://127.0.0.1:1")
        val store = store(list = listOf(spec))
        cache.mkdirs()
        val zip = File(cache, "pack.zip.part").apply { writeBytes(archive) }
        val dir = File(models, "pack").apply { mkdirs() }
        // Being unpacked (no manifest yet): the zip is still needed.
        File(dir, "a.onnx").writeBytes(packed.getValue("a.onnx"))
        assertTrue(store.removeRetired())
        assertTrue(zip.exists())
        // Another archive's folder: retired; the zip may be the new one's download, kept.
        val entries = packed.mapValues { (_, b) -> ModelStore.Entry(b.size.toLong(), sha256(b)) }
        File(dir, ModelStore.MANIFEST).writeText(ModelStore.json.encodeToString(ModelStore.Manifest.serializer(), ModelStore.Manifest("float zip", entries)))
        assertTrue(store.removeRetired())
        assertFalse(dir.exists())
        assertTrue(zip.exists())
        // In place (the app died after writing the manifest, or the zip could not be deleted): the zip goes.
        dir.mkdirs()
        packed.forEach { (name, b) -> File(dir, name).writeBytes(b) }
        File(dir, ModelStore.MANIFEST).writeText(ModelStore.json.encodeToString(ModelStore.Manifest.serializer(), ModelStore.Manifest(spec.sha256, entries)))
        assertTrue(store.has(spec))
        assertEquals(archive.size.toLong(), store.retiredBytes())
        assertTrue(store.removeRetired())
        assertFalse(zip.exists())
        assertTrue(store.has(spec))
        assertEquals(0L, store.retiredBytes())
    }

    @Test
    fun `delete also removes models an earlier version used, and an archive's folder`() {
        models.mkdirs()
        File(models, OLD).writeBytes(second)
        File(models, "pack").mkdirs()
        File(models, "pack/a.bin").writeBytes(second)
        cache.mkdirs()
        File(cache, "pack.zip.part").writeBytes(second)

        assertTrue(store(list = listOf(archiveSpec("http://127.0.0.1:1"))).delete())

        assertFalse(File(models, OLD).exists())
        assertFalse(File(models, "pack").exists())
        assertFalse(File(cache, "pack.zip.part").exists())
    }

    @Test
    fun `a download first deletes the models an earlier version used`() {
        val base = start()
        files["/first.bin"] = first
        files["/second.onnx"] = second
        models.mkdirs()
        File(models, OLD).writeBytes(second)

        val download = runBlocking {
            ModelDownloader(store(base), "Lyrify/test", loopback, Dispatchers.IO, freeBytes = { Long.MAX_VALUE }).downloadAll { _, _ -> }
        }

        assertEquals(Download.Done, download)
        assertEquals(setOf("first.bin", "second.onnx"), models.list().orEmpty().toSet())
    }

    @Test
    fun `a model that does not load is deleted, only that file, and the models are not ready`() {
        models.mkdirs()
        File(models, "first.bin").writeBytes(first)
        File(models, "second.onnx").writeBytes(second)
        val elsewhere = File(folder.root, "first.bin").apply { writeBytes(first) }
        val other = File(models, "notes.txt").apply { writeText("kept") }
        val store = store()
        assertTrue(store.isReady())

        assertFalse(store.discard(elsewhere))
        assertFalse(store.discard(other))
        assertTrue(store.discard(File(models, "first.bin")))

        assertFalse(File(models, "first.bin").exists())
        assertTrue(File(models, "second.onnx").exists())
        assertTrue(elsewhere.exists())
        assertTrue(other.exists())
        assertFalse(store.isReady())
    }

    @Test
    fun `a download that got further before it was cut goes on at once, one that got nowhere stops`() = runBlocking {
        var bytes = 0L
        val tries = mutableListOf<Long>()
        val result = retryWhileProgressing(bytes = { bytes }) {
            tries += bytes
            if (bytes < 300) {
                bytes += 100
                Download.Interrupted("connection closed")
            } else {
                Download.Interrupted("connection refused")
            }
        }
        assertEquals(Download.Interrupted("connection refused"), result)
        assertEquals(listOf(0L, 100L, 200L, 300L), tries)

        var runs = 0
        assertEquals(Download.Done, retryWhileProgressing(bytes = { runs * 100L }) { runs++; Download.Done })
        assertEquals(Download.Failed("checksum"), retryWhileProgressing(bytes = { runs * 100L }) { runs++; Download.Failed("checksum") })
        assertEquals(2, runs)
    }

    // Downloading, from a local server

    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var server: HttpServer? = null

    /** Serves [files] by path, with Range support unless a test changes [handle]. */
    private var handle: (HttpExchange, ByteArray?) -> Unit = { exchange, body -> serveRange(exchange, body) }
    private val files = mutableMapOf<String, ByteArray>()

    private fun start(): String {
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        created.createContext("/") { exchange ->
            try {
                requests += "${exchange.requestURI.path} ${exchange.requestHeaders.getFirst("Range").orEmpty()}".trim()
                handle(exchange, files[exchange.requestURI.path.removePrefix("/cdn").replace("/repo/", "/")])
            } finally {
                exchange.close()
            }
        }
        created.start()
        server = created
        return "http://127.0.0.1:${created.address.port}"
    }

    @After
    fun stop() {
        server?.stop(0)
    }

    private fun serveRange(exchange: HttpExchange, body: ByteArray?, cutAfter: Int = Int.MAX_VALUE) {
        if (body == null) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
        val from = range ?: 0
        val sent = body.copyOfRange(from, body.size)
        if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
        exchange.sendResponseHeaders(if (range != null) 206 else 200, sent.size.toLong())
        exchange.responseBody.write(sent, 0, minOf(sent.size, cutAfter))
    }

    /** Loopback stands in for Hugging Face; "localhost" for any other host. */
    private val loopback = object : ModelHosts {
        override fun isSource(uri: URI): Boolean = uri.host == "127.0.0.1"

        override fun isRedirect(uri: URI): Boolean = uri.host == "127.0.0.1"
    }

    private fun downloader(base: String, free: Long = Long.MAX_VALUE, list: List<ModelSpec> = specs(base)) =
        ModelDownloader(store(base, list), userAgent = "Lyrify/test", hosts = loopback, io = Dispatchers.IO, freeBytes = { free })

    private fun download(base: String, progress: MutableList<Long> = mutableListOf(), free: Long = Long.MAX_VALUE, list: List<ModelSpec> = specs(base)): Download =
        runBlocking { downloader(base, free, list).downloadAll { done, _ -> progress += done } }

    @Test
    fun `every model is downloaded, checked and moved into place`() {
        val base = start()
        files["/first.bin"] = first
        files["/second.onnx"] = second
        val progress = mutableListOf<Long>()

        assertEquals(Download.Done, download(base, progress))

        assertArrayEquals(first, File(models, "first.bin").readBytes())
        assertArrayEquals(second, File(models, "second.onnx").readBytes())
        assertEquals(setOf("first.bin", "second.onnx"), models.list().orEmpty().toSet())
        assertEquals(progress.sorted(), progress)
        assertEquals(420_000L, progress.last())
        assertTrue(progress.size in 2..102)
        assertTrue(store(base).isReady())
    }

    @Test
    fun `a part goes on from where it stopped`() {
        val base = start()
        files["/first.bin"] = first
        files["/second.onnx"] = second
        models.mkdirs()
        File(models, "first.bin.part").writeBytes(first.copyOf(100_000))

        assertEquals(Download.Done, download(base))

        assertArrayEquals(first, File(models, "first.bin").readBytes())
        assertEquals(listOf("/repo/first.bin bytes=100000-", "/repo/second.onnx"), requests)
    }

    @Test
    fun `a server that ignores the range sends it all again, and the part starts over`() {
        val base = start()
        files["/first.bin"] = first
        files["/second.onnx"] = second
        handle = { exchange, body ->
            exchange.sendResponseHeaders(200, body?.size?.toLong() ?: -1)
            body?.let { exchange.responseBody.write(it) }
        }
        models.mkdirs()
        File(models, "first.bin.part").writeBytes(ByteArray(50_000) { 9 })

        assertEquals(Download.Done, download(base))

        assertArrayEquals(first, File(models, "first.bin").readBytes())
    }

    @Test
    fun `a file that does not match its hash is deleted and the download fails`() {
        val base = start()
        files["/first.bin"] = bytes(first.size, 99)
        files["/second.onnx"] = second

        val result = download(base)

        assertTrue(result is Download.Failed)
        assertFalse(File(models, "first.bin").exists())
        assertFalse(File(models, "first.bin.part").exists())
        assertEquals(listOf("/repo/first.bin"), requests)
    }

    @Test
    fun `a file of another size fails before it is kept`() {
        val base = start()
        files["/first.bin"] = first.copyOf(first.size + 10)
        files["/second.onnx"] = second

        assertTrue(download(base) is Download.Failed)

        assertFalse(File(models, "first.bin.part").exists())
        assertFalse(File(models, "first.bin").exists())
    }

    @Test
    fun `a dropped connection keeps the part, and the next run finishes it`() {
        val base = start()
        files["/first.bin"] = first
        files["/second.onnx"] = second
        handle = { exchange, body -> serveRange(exchange, body, cutAfter = 70_000) }

        assertTrue(download(base) is Download.Interrupted)
        val kept = File(models, "first.bin.part").length()
        assertTrue("kept $kept", kept in 1..first.size.toLong() - 1)

        handle = { exchange, body -> serveRange(exchange, body) }
        assertEquals(Download.Done, download(base))
        assertArrayEquals(first, File(models, "first.bin").readBytes())
        assertTrue(requests.contains("/repo/first.bin bytes=$kept-"))
    }

    @Test
    fun `a redirect to an allowed host is followed with the range`() {
        val base = start()
        files["/first.bin"] = first
        files["/second.onnx"] = second
        handle = { exchange, body ->
            if (exchange.requestURI.path.startsWith("/repo/")) {
                exchange.responseHeaders.add("Location", "$base/cdn/${exchange.requestURI.path.removePrefix("/repo/")}?sig=1")
                exchange.sendResponseHeaders(302, -1)
            } else {
                serveRange(exchange, body)
            }
        }
        models.mkdirs()
        File(models, "first.bin.part").writeBytes(first.copyOf(10))

        assertEquals(Download.Done, download(base))

        assertEquals(listOf("/repo/first.bin bytes=10-", "/cdn/first.bin bytes=10-", "/repo/second.onnx", "/cdn/second.onnx"), requests)
    }

    @Test
    fun `a redirect anywhere else is refused before it is followed`() {
        val base = start()
        files["/first.bin"] = first
        handle = { exchange, _ ->
            exchange.responseHeaders.add("Location", "http://localhost:${server?.address?.port}/cdn/first.bin")
            exchange.sendResponseHeaders(302, -1)
        }

        assertTrue(download(base) is Download.Failed)

        assertEquals(listOf("/repo/first.bin"), requests)
    }

    @Test
    fun `endless redirects fail`() {
        val base = start()
        handle = { exchange, _ ->
            exchange.responseHeaders.add("Location", "$base/repo/first.bin")
            exchange.sendResponseHeaders(302, -1)
        }

        assertTrue(download(base) is Download.Failed)

        assertTrue(requests.size <= 6)
    }

    @Test
    fun `a model gone from the server fails, a busy server is tried again later`() {
        val base = start()
        assertTrue(download(base) is Download.Failed)
        handle = { exchange, _ -> exchange.sendResponseHeaders(503, -1) }
        assertTrue(download(base) is Download.Interrupted)
    }

    @Test
    fun `a source off the allowed hosts is never fetched`() {
        start()
        val elsewhere = "http://localhost:${server?.address?.port}"

        assertTrue(download(elsewhere) is Download.Failed)

        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `models already in place are not fetched again`() {
        val base = start()
        models.mkdirs()
        File(models, "first.bin").writeBytes(first)
        File(models, "second.onnx").writeBytes(second)

        assertEquals(Download.Done, download(base))

        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `without room for the rest nothing is fetched`() {
        val base = start()
        files["/first.bin"] = first

        assertTrue(download(base, free = 1_000) is Download.Interrupted)

        assertEquals(emptyList<String>(), requests)
    }

    // Archives (AI Hub's Whisper zip)

    /** What the test archive holds, in its folder "pack-v1": two files, a big one deflated across many buffers. */
    private val packed: Map<String, ByteArray> = linkedMapOf("a.onnx" to bytes(1_000, 3), "b.bin" to bytes(2_500_000, 5))

    private fun zip(entries: Map<String, ByteArray>, folder: String = "pack-v1", extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("$folder/"))
            zip.closeEntry()
            (entries.mapKeys { "$folder/${it.key}" } + extra).forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val archive: ByteArray by lazy { zip(packed) }

    private fun archiveSpec(base: String, bytes: ByteArray = archive) =
        ModelSpec("pack", "$base/repo/pack.zip", sha256(bytes), bytes.size.toLong(), ModelSpec.Unpack("pack-v1", packed.keys.toList(), packed.values.sumOf { it.size.toLong() }))

    @Test
    fun `an archive is downloaded into the cache, checked, unpacked through parts with a manifest, and the zip deleted`() {
        val base = start()
        files["/pack.zip"] = archive
        val spec = archiveSpec(base)

        assertEquals(Download.Done, download(base, list = listOf(spec)))

        val dir = File(models, "pack")
        packed.forEach { (name, bytes) -> assertArrayEquals(name, bytes, File(dir, name).readBytes()) }
        assertEquals(setOf("a.onnx", "b.bin", ModelStore.MANIFEST), dir.list().orEmpty().toSet())
        val manifest = checkNotNull(store(list = listOf(spec)).manifest(spec))
        assertEquals(spec.sha256, manifest.archiveSha256)
        assertEquals(packed.mapValues { (_, b) -> ModelStore.Entry(b.size.toLong(), sha256(b)) }, manifest.files)
        assertFalse(File(cache, "pack.zip.part").exists())
        assertTrue(store(list = listOf(spec)).isReady())
        assertEquals(spec.bytes, store(list = listOf(spec)).bytesOnDisk())
        // In place: not fetched again.
        requests.clear()
        assertEquals(Download.Done, download(base, list = listOf(spec)))
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `an archive download goes on from its part, and a complete part cut short of unpacking is unpacked again`() {
        val base = start()
        files["/pack.zip"] = archive
        val spec = archiveSpec(base)
        cache.mkdirs()
        File(cache, "pack.zip.part").writeBytes(archive.copyOf(1_000))

        assertEquals(Download.Done, download(base, list = listOf(spec)))
        assertEquals(listOf("/repo/pack.zip bytes=1000-"), requests)

        // The app died while unpacking: the zip is all there, the folder half done, no manifest.
        File(models, "pack/${ModelStore.MANIFEST}").delete()
        File(models, "pack/b.bin").writeBytes(ByteArray(10))
        File(cache, "pack.zip.part").writeBytes(archive)
        requests.clear()
        assertEquals(Download.Done, download(base, list = listOf(spec)))
        assertEquals(emptyList<String>(), requests)
        assertArrayEquals(packed.getValue("b.bin"), File(models, "pack/b.bin").readBytes())
        assertTrue(store(list = listOf(spec)).isReady())
    }

    @Test
    fun `an archive without the expected files fails and leaves nothing, and only listed names are written`() {
        val base = start()
        val lacking = zip(packed - "b.bin", extra = mapOf("pack-v1/../../escape.bin" to bytes(10, 1), "other/b.bin" to bytes(10, 2)))
        files["/pack.zip"] = lacking

        val result = download(base, list = listOf(archiveSpec(base, lacking)))

        assertTrue(result.toString(), result is Download.Failed)
        assertFalse(File(models, "pack").exists())
        assertFalse(File(cache, "pack.zip.part").exists())
        assertFalse(File(folder.root, "escape.bin").exists())
        assertFalse(File(models, "escape.bin").exists())
    }

    @Test
    fun `an archive that does not match its hash is deleted before it is unpacked`() {
        val base = start()
        files["/pack.zip"] = zip(packed + ("a.onnx" to bytes(1_000, 4)))

        assertTrue(download(base, list = listOf(archiveSpec(base))) is Download.Failed)

        assertFalse(File(models, "pack").exists())
        assertFalse(File(cache, "pack.zip.part").exists())
    }

    @Test
    fun `the room needed is what is left to download, plus an archive's unpacked files, plus a spare`() {
        val archiveSpec = archiveSpec("http://127.0.0.1:1")
        val store = store(list = specs("http://127.0.0.1:1") + archiveSpec)
        val unpacked = packed.values.sumOf { it.size.toLong() }
        assertEquals(420_000L + archive.size + unpacked + ModelStore.SPARE_BYTES, store.bytesNeeded())
        models.mkdirs()
        File(models, "first.bin").writeBytes(first)
        cache.mkdirs()
        File(cache, "pack.zip.part").writeBytes(archive.copyOf(100))
        assertEquals(120_000L + archive.size - 100 + unpacked + ModelStore.SPARE_BYTES, store.bytesNeeded())
        // The tested chip from nothing: the w8a16 zip and its files on disk together, about 1.8 GB; once unpacked the zip
        // goes, and the MDX and wav2vec2 files (157 MB) fit in its room. Not the 4.9 GB float and fp32 needed.
        val real = ModelStore(models, ModelSpec.forChip(NpuChips.ALL.first()), cache)
        assertEquals(824_020_794L + 983_166_696L + ModelStore.SPARE_BYTES, real.bytesNeeded())
    }

    @Test
    fun `the room needed is the most the disk holds on the way, archive first or last`() {
        val archiveSpec = archiveSpec("http://127.0.0.1:1")
        val unpacked = packed.values.sumOf { it.size.toLong() }
        // Archive first: its zip and files at once, then the files only; the two files come after the zip is gone.
        val archiveFirst = store(list = listOf(archiveSpec) + specs("http://127.0.0.1:1"))
        assertEquals(maxOf(archive.size + unpacked, unpacked + 420_000L) + ModelStore.SPARE_BYTES, archiveFirst.bytesNeeded())
        // Half the zip on disk already: only the rest of it counts, and the part is gone once unpacked.
        cache.mkdirs()
        File(cache, "pack.zip.part").writeBytes(archive.copyOf(archive.size / 2))
        val rest = archive.size - archive.size / 2L
        assertEquals(maxOf(rest + unpacked, unpacked - archive.size / 2 + 420_000L) + ModelStore.SPARE_BYTES, archiveFirst.bytesNeeded())
        // In place: nothing.
        File(cache, "pack.zip.part").delete()
        models.mkdirs()
        File(models, "first.bin").writeBytes(first)
        File(models, "second.onnx").writeBytes(second)
        assertEquals(0L, store().bytesNeeded())
    }

    @Test
    fun `without room for the rest and the unpacked files, nothing is fetched`() {
        val base = start()
        files["/pack.zip"] = archive
        val spec = archiveSpec(base)
        val needed = archive.size + packed.values.sumOf { it.size.toLong() } + ModelStore.SPARE_BYTES

        assertTrue(download(base, free = needed - 1, list = listOf(spec)) is Download.Interrupted)
        assertEquals(emptyList<String>(), requests)
        assertEquals(Download.Done, download(base, free = needed, list = listOf(spec)))
    }

    // What the card shows

    @Test
    fun `model state from the files, the download work and the free space`() {
        val total = 1_000L
        assertEquals(ModelState.Ready(total), modelState(ready = true, onDisk = total, total = total, phase = DownloadPhase.FAILED, doneBytes = null))
        assertEquals(ModelState.Missing(0, total), modelState(ready = false, onDisk = 0, total = total, phase = null, doneBytes = null))
        assertEquals(ModelState.Missing(300, total), modelState(ready = false, onDisk = 300, total = total, phase = null, doneBytes = null))
        assertEquals(
            ModelState.Downloading(400, total, waiting = false),
            modelState(ready = false, onDisk = 300, total = total, phase = DownloadPhase.RUNNING, doneBytes = 400),
        )
        assertEquals(
            ModelState.Downloading(300, total, waiting = false),
            modelState(ready = false, onDisk = 300, total = total, phase = DownloadPhase.RUNNING, doneBytes = null),
        )
        assertEquals(
            ModelState.Downloading(300, total, waiting = true),
            modelState(ready = false, onDisk = 300, total = total, phase = DownloadPhase.WAITING, doneBytes = null),
        )
        assertEquals(ModelState.Failed(300, total), modelState(ready = false, onDisk = 300, total = total, phase = DownloadPhase.FAILED, doneBytes = null))
        assertEquals(40, ModelState.Downloading(400, total, waiting = false).percent)
        // Not enough room: said so while the download waits or before it starts, not while it runs or once in place.
        assertEquals(
            ModelState.NoRoom(300, total, neededBytes = 5_000),
            modelState(ready = false, onDisk = 300, total = total, phase = DownloadPhase.WAITING, doneBytes = null, neededBytes = 5_000, freeBytes = 4_999),
        )
        assertEquals(
            ModelState.NoRoom(0, total, neededBytes = 5_000),
            modelState(ready = false, onDisk = 0, total = total, phase = null, doneBytes = null, neededBytes = 5_000, freeBytes = 10),
        )
        assertEquals(
            ModelState.Missing(0, total),
            modelState(ready = false, onDisk = 0, total = total, phase = null, doneBytes = null, neededBytes = 5_000, freeBytes = 5_000),
        )
        assertEquals(ModelState.Ready(total), modelState(ready = true, onDisk = total, total = total, phase = null, doneBytes = null, neededBytes = 0, freeBytes = 0))
    }

    private companion object {
        /** A model file an earlier version downloaded ([ModelSpec.RETIRED]). */
        const val OLD = "ggml-large-v3-turbo-q5_0.bin"
    }

    // A worker going into the foreground (both workers' setForeground)

    @Test
    fun `a worker the phone lets into the foreground says so, one it does not runs on as an ordinary job`() = runBlocking {
        val refused = mutableListOf<String>()
        assertTrue(inForeground({}) { refused += it.message.orEmpty() })
        // ForegroundServiceStartNotAllowedException is an IllegalStateException.
        assertFalse(inForeground({ throw IllegalStateException("not allowed now") }) { refused += it.message.orEmpty() })
        assertEquals(listOf("not allowed now"), refused)
    }

    @Test
    fun `a worker cancelled while it goes into the foreground stays cancelled, not refused`() = runBlocking {
        // CancellationException is an IllegalStateException too: it must not read as "not allowed".
        var refused = false
        val thrown = runCatching { inForeground({ throw CancellationException("work cancelled") }) { refused = true } }.exceptionOrNull()
        assertTrue("$thrown", thrown is CancellationException)
        assertFalse(refused)
        // The same when WorkManager cancels the worker's coroutine while setForeground waits.
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            inForeground({
                started.complete(Unit)
                awaitCancellation()
            }) { refused = true }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(refused)
    }
}
