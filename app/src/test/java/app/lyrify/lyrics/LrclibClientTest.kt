package app.lyrify.lyrics

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class LrclibClientTest {
    private val nemoJson = """
        {"id":42,"name":"Nemo","trackName":"Nemo","artistName":"Nightwish","albumName":"Once",
         "duration":276.0,"instrumental":false,"plainLyrics":"a\nb","syncedLyrics":"[00:01.00]a","future":1}
    """.trimIndent()

    @Test
    fun `a record is read and unknown fields are ignored`() {
        val record = LrclibJson.record(nemoJson)
        assertEquals(42L, record.id)
        assertEquals("Nemo", record.trackName)
        assertEquals("Nightwish", record.artistName)
        assertEquals(276.0, record.duration ?: 0.0, 0.0)
        assertEquals("[00:01.00]a", record.syncedLyrics)
    }

    @Test
    fun `missing and null fields take their defaults`() {
        val record = LrclibJson.record("""{"id":7,"trackName":null,"instrumental":null,"duration":null}""")
        assertEquals(LrclibRecord(id = 7), record)
    }

    @Test
    fun `a search result is a list`() {
        assertEquals(listOf(42L, 42L), LrclibJson.records("[$nemoJson,$nemoJson]").map { it.id })
        assertEquals(emptyList<LrclibRecord>(), LrclibJson.records("[]"))
    }

    @Test
    fun `200 with a record is found`() {
        assertEquals(LrclibResult.Found(listOf(LrclibJson.record(nemoJson))), LrclibResponses.get(200, nemoJson))
        assertEquals(LrclibResult.Found(emptyList()), LrclibResponses.search(200, "[]"))
    }

    @Test
    fun `404 on get is not found`() {
        assertEquals(LrclibResult.NotFound, LrclibResponses.get(404, """{"code":404,"name":"TrackNotFound"}"""))
    }

    @Test
    fun `redirects are transient`() {
        assertTrue(LrclibResponses.get(301, null) is LrclibResult.Transient)
        assertTrue(LrclibResponses.search(302, "[]") is LrclibResult.Transient)
    }

    @Test
    fun `anything else is transient`() {
        assertTrue(LrclibResponses.get(503, "<html>busy</html>") is LrclibResult.Transient)
        assertTrue(LrclibResponses.get(500, null) is LrclibResult.Transient)
        assertTrue(LrclibResponses.get(429, "") is LrclibResult.Transient)
        assertTrue(LrclibResponses.search(404, "") is LrclibResult.Transient)
        assertTrue(LrclibResponses.get(200, "<html>overloaded</html>") is LrclibResult.Transient)
        assertTrue(LrclibResponses.search(200, "{\"not\":\"a list\"}") is LrclibResult.Transient)
        assertTrue(LrclibResponses.get(200, null) is LrclibResult.Transient)
    }

    @Test
    fun `the get url carries only artist, title, album and duration`() {
        assertEquals(
            "https://lrclib.net/api/get?artist_name=Mago+de+Oz&track_name=Fiesta+pagana+%282021%29&album_name=Finisterra&duration=276",
            LrclibUrls.get("https://lrclib.net", "Mago de Oz", "Fiesta pagana (2021)", "Finisterra", 276),
        )
        assertEquals(
            "https://lrclib.net/api/get?artist_name=AC%2FDC&track_name=T.N.T.&duration=215",
            LrclibUrls.get("https://lrclib.net", "AC/DC", "T.N.T.", null, 215),
        )
    }

    @Test
    fun `search uses names, or the title alone without an artist`() {
        assertEquals(
            "https://lrclib.net/api/search?track_name=G%C3%B6tterd%C3%A4mmerung&artist_name=Sabaton",
            LrclibUrls.search("https://lrclib.net", "Götterdämmerung", "Sabaton"),
        )
        assertEquals("https://lrclib.net/api/search?q=Metallica+-+One", LrclibUrls.search("https://lrclib.net", "Metallica - One", null))
    }

    @Test
    fun `requests run one at a time at least the spacing apart`() = runTest {
        val gate = RequestGate(now = { testScheduler.currentTime })
        val spans = mutableListOf<LongRange>()
        suspend fun call() = gate.run(spacingMs = 500) {
            val start = testScheduler.currentTime
            delay(300)
            spans += start..testScheduler.currentTime
        }
        val a = async { call() }
        val b = async { call() }
        val c = async { call() }
        a.await()
        b.await()
        c.await()
        assertEquals(listOf(0L..300L, 800L..1_100L, 1_600L..1_900L), spans)
    }

    @Test
    fun `a request long after the last one starts at once`() = runTest {
        val gate = RequestGate(now = { testScheduler.currentTime })
        gate.run(spacingMs = 500) { }
        delay(2_000)
        val before = testScheduler.currentTime
        gate.run(spacingMs = 500) { }
        assertEquals(before, testScheduler.currentTime)
    }

    @Test
    fun `low lane requests start 1,5 s after the previous one, high lane 500 ms`() = runTest {
        val gate = RequestGate(now = { testScheduler.currentTime })
        val starts = mutableListOf<Long>()
        suspend fun call(lane: Lane) = gate.run(lane.spacingMs) {
            starts += testScheduler.currentTime
            delay(100)
        }
        call(Lane.LOW)
        call(Lane.LOW)
        call(Lane.HIGH)
        call(Lane.LOW)
        assertEquals(listOf(0L, 1_600L, 2_200L, 3_800L), starts)
    }

    // Response bodies

    @Test
    fun `a body up to the cap is read whole, a larger one not at all`() {
        assertArrayEquals(ByteArray(10) { it.toByte() }, readCapped(ByteArrayInputStream(ByteArray(10) { it.toByte() }), maxBytes = 10))
        assertNull(readCapped(ByteArrayInputStream(ByteArray(11)), maxBytes = 10))
        assertArrayEquals(ByteArray(0), readCapped(ByteArrayInputStream(ByteArray(0)), maxBytes = 10))
    }

    @Test
    fun `bodies are capped at 2 MiB`() {
        assertEquals(2 * 1024 * 1024, HttpLrclibClient.MAX_BODY_BYTES)
    }

    // Against a local server

    private fun serve(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                try {
                    handler(exchange)
                } finally {
                    exchange.close()
                }
            }
            start()
        }

    private fun HttpServer.client() = HttpLrclibClient(userAgent = "Lyrify/test", base = "http://127.0.0.1:${address.port}")

    private fun HttpExchange.reply(code: Int, body: ByteArray) {
        sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.write(body)
    }

    @Test
    fun `a record from the server is found`() = runTest {
        val server = serve { it.reply(200, "[$nemoJson]".encodeToByteArray()) }
        try {
            assertEquals(LrclibResult.Found(listOf(LrclibJson.record(nemoJson))), server.client().search("Nemo", "Nightwish", Lane.HIGH))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `server errors are transient`() = runTest {
        val server = serve { it.reply(503, "<html>busy</html>".encodeToByteArray()) }
        try {
            val client = server.client()
            assertTrue(client.get("Nightwish", "Nemo", null, 276, Lane.HIGH) is LrclibResult.Transient)
            assertTrue(client.search("Nemo", null, Lane.HIGH) is LrclibResult.Transient)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a 404 from get is not found`() = runTest {
        val server = serve { it.reply(404, """{"code":404}""".encodeToByteArray()) }
        try {
            assertEquals(LrclibResult.NotFound, server.client().get("Nightwish", "Nemo", null, 276, Lane.HIGH))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `redirects are not followed`() = runTest {
        var elsewhere = 0
        val server = serve { exchange ->
            if (exchange.requestURI.path == "/elsewhere") {
                elsewhere++
                exchange.reply(200, "[$nemoJson]".encodeToByteArray())
            } else {
                exchange.responseHeaders.add("Location", "/elsewhere")
                exchange.reply(302, ByteArray(0))
            }
        }
        try {
            assertTrue(server.client().search("Nemo", "Nightwish", Lane.HIGH) is LrclibResult.Transient)
            assertEquals(0, elsewhere)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a body over the cap is transient`() = runTest {
        val huge = "[" + List(30_000) { nemoJson }.joinToString(",") + "]"
        assertTrue(huge.length > HttpLrclibClient.MAX_BODY_BYTES)
        val server = serve { it.reply(200, huge.encodeToByteArray()) }
        try {
            assertTrue(server.client().search("Nemo", "Nightwish", Lane.HIGH) is LrclibResult.Transient)
        } finally {
            server.stop(0)
        }
    }
}
