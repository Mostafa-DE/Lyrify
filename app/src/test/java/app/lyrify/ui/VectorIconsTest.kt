package app.lyrify.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min

/**
 * Every `res/drawable/ic_*.xml` vector draws something that shows. A path in another coordinate
 * system than its viewport (24 units in a 960 viewport, or Material Symbols' y from -960 to 0
 * left untranslated) builds fine and draws a speck or nothing at all.
 */
class VectorIconsTest {
    /** Unit tests run in the module's folder. */
    private val drawables = File("src/main/res/drawable")

    private val vectors: List<Pair<String, Element>> by lazy {
        val files = drawables.listFiles { file -> file.name.startsWith("ic_") && file.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        files.map { it.name to factory.newDocumentBuilder().parse(it).documentElement }.filter { it.second.tagName == "vector" }
    }

    @Test
    fun `the icons are found`() {
        assertTrue("no vectors in ${drawables.absolutePath}", vectors.size >= 50)
        assertTrue(vectors.any { it.first == "ic_auto_awesome.xml" })
    }

    @Test
    fun `every icon has a size, a viewport and paths with a colour`() {
        val problems = vectors.flatMap { (name, vector) ->
            val tinted = vector.attr("tint") != null
            listOfNotNull(
                "$name: no width or height".takeIf { vector.attr("width") == null || vector.attr("height") == null },
                "$name: no viewport".takeIf { viewport(vector) == null },
                "$name: no path".takeIf { paths(vector).isEmpty() },
            ) + paths(vector).flatMap { (path, _) ->
                listOfNotNull(
                    "$name: a path without pathData".takeIf { path.attr("pathData").isNullOrBlank() },
                    "$name: a path with no fillColor, strokeColor or tint".takeIf {
                        !tinted && path.attr("fillColor") == null && path.attr("strokeColor") == null
                    },
                )
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `every path lies in its viewport, and each icon spans at least a quarter of it`() {
        val problems = vectors.flatMap { (name, vector) ->
            val viewport = viewport(vector) ?: return@flatMap listOf("$name: no viewport")
            val boxes = paths(vector).map { (path, offset) -> bounds(path.attr("pathData").orEmpty())?.shifted(offset) }
            val drawn = boxes.filterNotNull().reduceOrNull(Box::plus)
            listOfNotNull(
                "$name: a path with no points".takeIf { boxes.any { it == null } },
                "$name: a path not inside the viewport $viewport".takeIf { boxes.any { it != null && it !in viewport } },
                "$name: draws $drawn, under a quarter of the viewport $viewport".takeIf {
                    drawn != null && max(drawn.width / viewport.width, drawn.height / viewport.height) < 0.25f
                },
            )
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `a path's bounds take relative moves, curves and arcs into account`() {
        assertEquals(Box(10f, 20f, 30f, 40f), bounds("M10,20L30,40Z"))
        assertEquals(Box(10f, 20f, 30f, 40f), bounds("m10,20 l20,20 z"))
        assertEquals(Box(100f, 100f, 300f, 200f), bounds("M100,100H300V200H100Z"))
        assertEquals(Box(0f, -5f, 20f, 10f), bounds("M0,0Q5,-5 10,0T20,10"))
        assertEquals(Box(33f, 35f, 67f, 41f), bounds("M33,35 h34 a3,3 0 0 1 0,6 h-34 a3,3 0 0 1 0,-6 z"))
        assertEquals(Box(1.5f, 0.5f, 2f, 1f), bounds("M2,1L1.5.5"))
        assertEquals(null, bounds(""))
    }

    /** A box in viewport units. */
    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        operator fun plus(other: Box): Box = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

        operator fun contains(other: Box): Boolean = left <= other.left && top <= other.top && other.right <= right && other.bottom <= bottom

        fun shifted(by: Pair<Float, Float>): Box = Box(left + by.first, top + by.second, right + by.first, bottom + by.second)
    }

    private fun Element.attr(name: String): String? = getAttributeNS(ANDROID, name).takeIf { it.isNotEmpty() }

    private fun viewport(vector: Element): Box? {
        val width = vector.attr("viewportWidth")?.toFloatOrNull() ?: return null
        val height = vector.attr("viewportHeight")?.toFloatOrNull() ?: return null
        return if (width > 0 && height > 0) Box(0f, 0f, width, height) else null
    }

    /** Every `<path>`, with the translation of the groups around it; a group's scale or rotation is not handled here. */
    private fun paths(element: Element, offset: Pair<Float, Float> = 0f to 0f): List<Pair<Element, Pair<Float, Float>>> =
        (0 until element.childNodes.length).map { element.childNodes.item(it) }.filterIsInstance<Element>().flatMap { child ->
            when (child.tagName) {
                "path" -> listOf(child to offset)
                "group" -> {
                    val unhandled = listOf("scaleX", "scaleY", "rotation").filter { child.attr(it) != null }
                    check(unhandled.isEmpty()) { "a group with $unhandled: teach VectorIconsTest about it" }
                    val x = child.attr("translateX")?.toFloat() ?: 0f
                    val y = child.attr("translateY")?.toFloat() ?: 0f
                    paths(child, offset.first + x to offset.second + y)
                }
                else -> emptyList()
            }
        }

    /**
     * The box around [data]'s points: every end point and curve control point, absolute or
     * relative (an arc counts by its end points). Rough, but enough to tell where a path draws.
     */
    private fun bounds(data: String): Box? {
        val scan = PathScanner(data)
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        fun point(px: Float, py: Float) {
            xs += px
            ys += py
        }
        while (true) {
            val command = scan.command() ?: break
            val relative = command.isLowerCase()
            var first = true
            do {
                val ox = if (relative) x else 0f
                val oy = if (relative) y else 0f
                when (command.uppercaseChar()) {
                    'Z' -> {
                        x = startX
                        y = startY
                    }
                    'H' -> x = ox + scan.number()
                    'V' -> y = oy + scan.number()
                    'C' -> {
                        point(ox + scan.number(), oy + scan.number())
                        point(ox + scan.number(), oy + scan.number())
                        x = ox + scan.number()
                        y = oy + scan.number()
                    }
                    'S', 'Q' -> {
                        point(ox + scan.number(), oy + scan.number())
                        x = ox + scan.number()
                        y = oy + scan.number()
                    }
                    'A' -> {
                        repeat(3) { scan.number() }
                        repeat(2) { scan.flag() }
                        x = ox + scan.number()
                        y = oy + scan.number()
                    }
                    else -> {
                        // M, L, T: a point; the pairs after a move's first are lines.
                        x = ox + scan.number()
                        y = oy + scan.number()
                        if (command.uppercaseChar() == 'M' && first) {
                            startX = x
                            startY = y
                        }
                    }
                }
                point(x, y)
                first = false
            } while (command.uppercaseChar() != 'Z' && scan.hasNumber())
        }
        if (xs.isEmpty()) return null
        return Box(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** Reads SVG path data: commands, numbers ("1.5.5" is 1.5 then .5) and arc flags ("01" is 0 then 1). */
    private class PathScanner(private val data: String) {
        private var at = 0

        fun command(): Char? {
            skipSeparators()
            return data.getOrNull(at)?.takeIf { it.isLetter() }?.also { at++ }
        }

        fun hasNumber(): Boolean {
            skipSeparators()
            return data.getOrNull(at)?.let { it.isDigit() || it in "+-." } == true
        }

        fun number(): Float {
            skipSeparators()
            val match = NUMBER.find(data, at)?.takeIf { it.range.first == at } ?: error("no number at $at in \"$data\"")
            at = match.range.last + 1
            return match.value.toFloat()
        }

        fun flag(): Float {
            skipSeparators()
            return data.getOrNull(at++)?.digitToIntOrNull()?.toFloat() ?: error("no arc flag at ${at - 1} in \"$data\"")
        }

        private fun skipSeparators() {
            while (at < data.length && (data[at].isWhitespace() || data[at] == ',')) at++
        }

        private companion object {
            val NUMBER = Regex("""[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?""")
        }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
