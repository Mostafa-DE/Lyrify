package app.lyrify.dupes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ContentHasherTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Bytes read from each file, and the files opened. */
    private val read = mutableMapOf<String, Long>()
    private val opened = mutableListOf<String>()

    /** 4-byte slices, full hashes under 32 bytes: the real 1 MiB and 64 MiB rules, small enough for temp files. */
    private val hasher = ContentHasher(sliceBytes = 4, fullLimit = 32, open = ::counting)

    private fun counting(path: Path): SeekableByteChannel {
        val channel = Files.newByteChannel(path, StandardOpenOption.READ)
        opened += path.fileName.toString()
        return object : SeekableByteChannel by channel {
            override fun read(dst: ByteBuffer): Int = channel.read(dst).also { if (it > 0) read.merge(path.fileName.toString(), it.toLong(), Long::plus) }
        }
    }

    private fun audio(name: String, bytes: ByteArray, id: Long = name.hashCode().toLong(), size: Long = bytes.size.toLong()): AudioFile {
        val file = File(folder.root, name)
        file.writeBytes(bytes)
        return AudioFile(
            id = id, path = file.path, relativePath = "Music/", displayName = name, title = name, artist = null, album = null,
            durationMs = 200_000, size = size, mimeType = "audio/mpeg", bitrate = null, addedAt = 0,
        )
    }

    private fun bytes(text: String): ByteArray = text.encodeToByteArray()

    @Test
    fun `the first and last slices are the whole file up to two slices`() {
        assertTrue(hasher.partialIsWhole(8))
        assertTrue(!hasher.partialIsWhole(9))
        assertTrue(!hasher.needsFull(8))
        assertTrue(hasher.needsFull(9))
        assertTrue(hasher.needsFull(31))
        assertTrue(!hasher.needsFull(32))
        assertTrue(ContentHasher().partialIsWhole(2L * 1024 * 1024))
        assertTrue(ContentHasher().needsFull(64L * 1024 * 1024 - 1))
        assertTrue(!ContentHasher().needsFull(64L * 1024 * 1024))
    }

    @Test
    fun `the partial hash reads only the first and the last slice`() = runTest {
        val file = audio("a.mp3", bytes("HEAD-the middle part-TAIL"))
        assertNotNull(hasher.partial(file))
        assertEquals(8L, read["a.mp3"])
    }

    @Test
    fun `the partial hash sees a different start or end`() = runTest {
        val a = hasher.partial(audio("a.mp3", bytes("HEAD-middle-TAIL")))
        assertEquals(a, hasher.partial(audio("b.mp3", bytes("HEAD-MIDDLE-TAIL"))))
        assertNotEquals(a, hasher.partial(audio("c.mp3", bytes("HEAX-middle-TAIL"))))
        assertNotEquals(a, hasher.partial(audio("d.mp3", bytes("HEAD-middle-TAIX"))))
    }

    @Test
    fun `the full hash reads every byte`() = runTest {
        val file = audio("a.mp3", bytes("HEAD-middle-TAIL"))
        val full = hasher.full(file)
        assertEquals(16L, read["a.mp3"])
        assertNotEquals(full, hasher.full(audio("b.mp3", bytes("HEAD-MIDDLE-TAIL"))))
    }

    @Test
    fun `a file whose size is not what MediaStore says is not hashed`() = runTest {
        val file = audio("a.mp3", bytes("HEAD-middle-TAIL"), size = 15)
        assertNull(hasher.partial(file))
        assertNull(hasher.full(file))
    }

    @Test
    fun `a missing file is not hashed`() = runTest {
        val file = audio("a.mp3", bytes("HEAD-middle-TAIL"))
        File(file.path).delete()
        assertNull(hasher.partial(file))
    }

    @Test
    fun `hashing never changes the file`() = runTest {
        val content = bytes("HEAD-middle-TAIL")
        val file = audio("a.mp3", content)
        File(file.path).setLastModified(1_700_000_000_000)
        hasher.partial(file)
        hasher.full(file)
        assertArrayEquals(content, File(file.path).readBytes())
        assertEquals(1_700_000_000_000, File(file.path).lastModified())
    }

    // Content keys

    @Test
    fun `only files that share a size with another file are opened`() = runTest {
        val files = listOf(audio("a.mp3", bytes("HEAD-x-TAIL")), audio("b.mp3", bytes("HEAD-x-TAIL")), audio("c.mp3", bytes("lonely")))
        val keys = identicalKeys(files, hasher)
        assertEquals(setOf("a.mp3", "b.mp3"), opened.toSet())
        assertEquals(keys[files[0].id], keys[files[1].id])
        assertNull(keys[files[2].id])
    }

    @Test
    fun `small files read whole once are identical without a full hash`() = runTest {
        val files = listOf(audio("a.mp3", bytes("same")), audio("b.mp3", bytes("same")))
        val keys = identicalKeys(files, hasher)
        assertEquals(keys[files[0].id], keys[files[1].id])
        assertEquals(listOf("a.mp3", "b.mp3"), opened)
    }

    @Test
    fun `equal slices under the full limit take the full hash, which tells a different middle`() = runTest {
        val files = listOf(audio("a.mp3", bytes("HEAD-middle-TAIL")), audio("b.mp3", bytes("HEAD-MIDDLE-TAIL")))
        val keys = identicalKeys(files, hasher)
        assertNotEquals(keys[files[0].id], keys[files[1].id])
        assertEquals(8L + 16L, read["a.mp3"])
    }

    @Test
    fun `equal slices under the full limit with the same middle are identical`() = runTest {
        val files = listOf(audio("a.mp3", bytes("HEAD-middle-TAIL")), audio("b.mp3", bytes("HEAD-middle-TAIL")))
        val keys = identicalKeys(files, hasher)
        assertNotNull(keys[files[0].id])
        assertEquals(keys[files[0].id], keys[files[1].id])
    }

    @Test
    fun `different slices need no full hash`() = runTest {
        val files = listOf(audio("a.mp3", bytes("HEAD-middle-TAIL")), audio("b.mp3", bytes("HEAX-middle-TAIL")))
        val keys = identicalKeys(files, hasher)
        assertNotEquals(keys[files[0].id], keys[files[1].id])
        assertEquals(8L, read["a.mp3"])
        assertEquals(8L, read["b.mp3"])
    }

    @Test
    fun `from the full limit on, equal slices and size are taken as identical without reading the rest`() = runTest {
        val middle = "m".repeat(30)
        val files = listOf(audio("a.mp3", bytes("HEAD${middle}TAIL")), audio("b.mp3", bytes("HEAD${middle.uppercase()}TAIL")))
        val keys = identicalKeys(files, hasher)
        assertEquals(keys[files[0].id], keys[files[1].id])
        assertEquals(8L, read["a.mp3"])
        assertEquals(8L, read["b.mp3"])
    }

    @Test
    fun `a file that cannot be read gets no key, and its peers still do`() = runTest {
        val files = listOf(audio("a.mp3", bytes("same")), audio("b.mp3", bytes("same")), audio("c.mp3", bytes("same")))
        File(files[1].path).delete()
        val keys = identicalKeys(files, hasher)
        assertNull(keys[files[1].id])
        assertEquals(keys[files[0].id], keys[files[2].id])
    }

    @Test
    fun `empty files are never hashed`() = runTest {
        val files = listOf(audio("a.mp3", ByteArray(0)), audio("b.mp3", ByteArray(0)))
        assertTrue(identicalKeys(files, hasher).isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `progress counts every file once`() = runTest {
        val files = listOf(audio("a.mp3", bytes("same")), audio("b.mp3", bytes("same")), audio("c.mp3", bytes("lonely size")))
        val done = mutableListOf<Int>()
        identicalKeys(files, hasher) { done += it }
        assertEquals(listOf(1, 2, 3), done)
    }
}
