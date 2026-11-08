package app.lyrify.ui

import android.os.Build
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.media.CurrentTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PATH = "/music/Paper Lanterns.mp3"

@OptIn(ExperimentalCoroutinesApi::class)
class ChromeTest {
    // The toast queue

    @Test
    fun `messages show one at a time, in the order they were asked for`() = runTest {
        val toasts = ToastState()
        val first = async { toasts.show(Toast("Synced lyrics added")) }
        val second = async { toasts.show(Toast("Lyrics removed")) }
        runCurrent()
        assertEquals("Synced lyrics added", toasts.current?.toast?.text)
        toasts.current?.dismiss()
        runCurrent()
        assertEquals("Lyrics removed", toasts.current?.toast?.text)
        assertFalse(first.isActive)
        toasts.current?.dismiss()
        runCurrent()
        assertNull(toasts.current)
        assertFalse(second.isActive)
    }

    @Test
    fun `the caller hears whether the message's action was tapped`() = runTest {
        val toasts = ToastState()
        val acted = async { toasts.show(Toast("Song moved to trash", action = "Undo")) }
        runCurrent()
        toasts.current?.dismiss(acted = true)
        assertTrue(acted.await())
        val timedOut = async { toasts.show("Tone deleted") }
        runCurrent()
        toasts.current?.dismiss()
        assertFalse(timedOut.await())
    }

    @Test
    fun `a message whose caller went away leaves the screen`() = runTest {
        val toasts = ToastState()
        val shown = async { toasts.show("Tone deleted") }
        runCurrent()
        shown.cancel()
        runCurrent()
        assertNull(toasts.current)
    }

    @Test
    fun `the newest slot on screen shows the message, and the one under it takes over when it leaves`() {
        val toasts = ToastState()
        val shell = Any()
        val sheet = Any()
        toasts.register(shell)
        assertTrue(toasts.showsIn(shell))
        toasts.register(sheet)
        assertFalse(toasts.showsIn(shell))
        assertTrue(toasts.showsIn(sheet))
        toasts.unregister(sheet)
        assertTrue(toasts.showsIn(shell))
        toasts.unregister(shell)
        assertFalse(toasts.showsIn(shell))
    }

    @Test
    fun `without the app's ToastState a debug build fails loud`() {
        assertThrows(IllegalStateException::class.java) { missingToasts(debug = true) }
    }

    @Test
    fun `without it a release build drops a message at once instead of waiting for ever`() = runTest {
        val toasts = missingToasts(debug = false)
        assertEquals(false, withTimeoutOrNull(1_000) { toasts.show("Tone deleted") })
        assertNull(toasts.current)
    }

    @Test
    fun `a message stays 4 seconds, 6 with an action or two lines`() {
        assertEquals(4_000L, toastMillis(Toast("Tone deleted")))
        assertEquals(6_000L, toastMillis(Toast("Tone deleted", action = "Undo")))
        assertEquals(6_000L, toastMillis(Toast("Couldn't open your Clock app. Open it from your home screen.")))
    }

    @Test
    fun `messages gain an icon and a tone where one fits, failures in the error tone`() {
        assertEquals(Toast("a", R.drawable.ic_lyrics, Tone.SYNCED), messageToast(R.string.check_done_synced, "a"))
        assertEquals(Toast("a", R.drawable.ic_delete, Tone.NONE), messageToast(R.string.removal_done, "a"))
        assertEquals(Toast("a", R.drawable.ic_delete, Tone.NONE), messageToast(R.plurals.dupes_moved, "a"))
        assertEquals(Toast("a", R.drawable.ic_restore_from_trash, Tone.SYNCED), messageToast(R.string.dupes_restored, "a"))
        assertEquals(Toast("a", R.drawable.ic_sync_problem, Tone.ERROR), messageToast(R.string.check_not_saved, "a"))
        listOf(R.string.removal_failed, R.string.dupes_trash_failed, R.string.tones_not_saved, R.string.tones_clock_not_opened).forEach {
            assertEquals(Toast("a", R.drawable.ic_error, Tone.ERROR), messageToast(it, "a"))
        }
        assertEquals(Toast("a"), messageToast(R.string.check_changed, "a"))
    }

    // The dock

    @Test
    fun `badges count the songs to check and the groups to review, and dot the AI`() {
        assertEquals(emptyMap<Tab, TabBadge>(), tabBadges(checks = 0, duplicates = 0, aiAttention = false))
        assertEquals(
            mapOf(
                Tab.NOW to TabBadge(3, R.plurals.nav_check_badge),
                Tab.DUPLICATES to TabBadge(4, R.plurals.nav_duplicates_badge),
                Tab.AI to TabBadge(null, R.string.nav_ai_attention),
            ),
            tabBadges(checks = 3, duplicates = 4, aiAttention = true),
        )
    }

    @Test
    fun `Now is the orb with its ring, the other tabs the sung line under their place in the capsule`() {
        assertEquals(listOf(Tab.SONGS, Tab.DUPLICATES, Tab.TONES, Tab.AI), CAPSULE_TABS)
        assertEquals(DockMark(orb = true, line = null), dockMark(Tab.NOW))
        assertEquals(DockMark(orb = false, line = 0), dockMark(Tab.SONGS))
        assertEquals(DockMark(orb = false, line = 1), dockMark(Tab.DUPLICATES))
        assertEquals(DockMark(orb = false, line = 2), dockMark(Tab.TONES))
        assertEquals(DockMark(orb = false, line = 3), dockMark(Tab.AI))
    }

    @Test
    fun `a dock item's name folds in what is playing and its badge`() {
        assertEquals("Now, Paper Lanterns, 3 songs to check", dockItemName("Now", "Paper Lanterns", "3 songs to check"))
        assertEquals("AI", dockItemName("AI", null))
    }

    @Test
    fun `the orb shows the playing song's cover and status, the brand mark when nothing plays`() {
        val entry = LedgerEntry(path = PATH, title = "Paper Lanterns", durationMs = 200_000, status = LyricsStatus.SYNCED, updatedAt = 0)
        val ledger = LedgerState(entries = mapOf(PATH to entry))
        assertEquals(NowOrb(cover = null, status = null, title = null, idle = true), nowOrb(null, ledger))
        val track = CurrentTrack(title = "Paper Lanterns", artist = null, paths = listOf("/music/other.mp3", PATH), resolved = true)
        assertEquals(NowOrb(SongArt("/music/other.mp3"), LyricsStatus.SYNCED, "Paper Lanterns", idle = false), nowOrb(track, ledger))
        assertEquals(NowOrb(null, null, "Radio", idle = false), nowOrb(CurrentTrack(title = "Radio", artist = null), ledger))
    }

    @Test
    fun `a new tab slides in from the side of the tab tapped`() {
        assertEquals(1, tabSlide(Tab.SONGS, Tab.DUPLICATES))
        assertEquals(-1, tabSlide(Tab.AI, Tab.NOW))
        assertEquals(0, tabSlide(Tab.TONES, Tab.TONES))
    }

    // Dialogs

    @Test
    fun `a dialog's buttons read Cancel first, then the confirm, stacked or side by side`() {
        assertEquals(listOf(DialogButton.DISMISS, DialogButton.CONFIRM), DIALOG_BUTTONS)
    }

    // Foundations

    @Test
    fun `contrast is the WCAG ratio`() {
        assertEquals(21f, contrast(Color.Black, Color.White), 0.01f)
        assertEquals(21f, contrast(Color.White, Color.Black), 0.01f)
        assertEquals(1f, contrast(Color.White, Color.White), 0.001f)
    }

    @Test
    fun `glass keeps text at 4_5 to 1 and the coral at 3 to 1 over the brightest or the darkest content, light and dark`() {
        listOf(Triple(LightColors, LightBrandColors, false), Triple(DarkColors, DarkBrandColors, true)).forEach { (scheme, brand, dark) ->
            val chrome = chromeColors(scheme, brand, dark)
            // The worst content for the glass: white under dark glass, black under light glass.
            val worst = if (dark) Color.White else Color.Black
            listOf(chrome.glass, chrome.glassSolid).forEach { glass ->
                val tint = glass.compositeOver(worst)
                val name = "dark $dark, glass ${glass.alpha}"
                assertTrue("$name: muted text ${contrast(scheme.onSurfaceVariant, tint)}", contrast(scheme.onSurfaceVariant, tint) >= 4.5f)
                assertTrue("$name: text ${contrast(scheme.onSurface, tint)}", contrast(scheme.onSurface, tint) >= 4.5f)
                assertTrue("$name: coral ${contrast(brand.highlight, tint)}", contrast(brand.highlight, tint) >= 3f)
            }
            assertTrue(chrome.glass.alpha >= 0.72f)
        }
    }

    @Test
    fun `glass is no more opaque than it needs to be`() {
        assertEquals(0.72f, glassAlpha(Color.White, text = Color.Black, mark = Color.Black, behind = Color.Black), 0.001f)
        assertEquals(1f, glassAlpha(Color.Gray, text = Color.Gray, mark = Color.Gray, behind = Color.White), 0.001f)
    }

    @Test
    fun `glass blurs on Android 12 and later, not in Battery Saver or with raised contrast`() {
        assertTrue(glassBlurs(Build.VERSION_CODES.S, powerSave = false, contrast = 0f))
        assertFalse(glassBlurs(Build.VERSION_CODES.R, powerSave = false, contrast = 0f))
        assertFalse(glassBlurs(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, powerSave = true, contrast = 0f))
        assertFalse(glassBlurs(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, powerSave = false, contrast = 0.5f))
    }

    @Test
    fun `with reduced motion every motion snaps and nothing squashes`() {
        assertTrue(motion(Motion.indicator, reduced = true) is SnapSpec)
        assertTrue(motion(tween<Float>(180), reduced = true) is SnapSpec)
        assertSame(Motion.indicator, motion(Motion.indicator, reduced = false))
        assertEquals(1f, pressScale(0.92f, reduced = true))
        assertEquals(0.92f, pressScale(0.92f, reduced = false))
    }
}
