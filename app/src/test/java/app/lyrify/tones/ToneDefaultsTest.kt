package app.lyrify.tones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneDefaultsTest {
    private val ringtone = tone(41, name = "Paper Lanterns (Lyrify)", kind = ToneKind.RINGTONE)
    private val alarm = tone(42, name = "Glass Harbor (Lyrify)", kind = ToneKind.ALARM)

    // Setting a default

    @Test
    fun `without Modify system settings, its screen opens and nothing is set`() {
        val sounds = FakeSounds(writable = false)
        assertEquals(DefaultOutcome.AskedPermission, ToneDefaults(sounds).set(ringtone, ToneKind.RINGTONE))
        assertEquals(listOf("openWriteSettings"), sounds.calls)
    }

    @Test
    fun `a phone without that screen says so`() {
        val sounds = FakeSounds(writable = false).apply { settingsScreen = false }
        assertEquals(DefaultOutcome.NoPermissionScreen, ToneDefaults(sounds).set(ringtone, ToneKind.RINGTONE))
    }

    @Test
    fun `with the permission, the tone becomes the default of the kind asked`() {
        val sounds = FakeSounds(writable = true)
        assertEquals(DefaultOutcome.Set(ToneKind.RINGTONE), ToneDefaults(sounds).set(ringtone, ToneKind.RINGTONE))
        assertEquals(DefaultOutcome.Set(ToneKind.NOTIFICATION), ToneDefaults(sounds).set(ringtone, ToneKind.NOTIFICATION))
        assertEquals(listOf("setDefault RINGTONE 41", "setDefault NOTIFICATION 41"), sounds.calls)
    }

    @Test
    fun `an alarm sound comes with the Clock guidance, naming the tone as the Clock lists it`() {
        val sounds = FakeSounds(writable = true)
        assertEquals(DefaultOutcome.AlarmSet("Glass Harbor (Lyrify)"), ToneDefaults(sounds).set(alarm, ToneKind.ALARM))
        // Any tone can be the alarm sound, not only one saved as an alarm.
        assertEquals(DefaultOutcome.AlarmSet("Paper Lanterns (Lyrify)"), ToneDefaults(sounds).set(ringtone, ToneKind.ALARM))
        assertEquals(listOf("setDefault ALARM 42", "setDefault ALARM 41"), sounds.calls)
    }

    @Test
    fun `a default the system did not take is reported`() {
        val sounds = FakeSounds(writable = true).apply { takes = false }
        assertEquals(DefaultOutcome.NotSet, ToneDefaults(sounds).set(ringtone, ToneKind.RINGTONE))
    }

    @Test
    fun `a permission taken back meanwhile asks for it again`() {
        val sounds = FakeSounds(writable = true).apply { revoked = true }
        assertEquals(DefaultOutcome.AskedPermission, ToneDefaults(sounds).set(ringtone, ToneKind.RINGTONE))
        assertEquals(listOf("openWriteSettings"), sounds.calls)
    }

    @Test
    fun `the phone's sound settings open for a second SIM`() {
        val sounds = FakeSounds()
        assertTrue(ToneDefaults(sounds).openSoundSettings())
        sounds.soundSettings = false
        assertFalse(ToneDefaults(sounds).openSoundSettings())
        assertEquals(listOf("openSoundSettings", "openSoundSettings"), sounds.calls)
    }

    @Test
    fun `whether Set works at once is the permission's state`() {
        assertFalse(ToneDefaults(FakeSounds(writable = false)).canWrite())
        assertTrue(ToneDefaults(FakeSounds(writable = true)).canWrite())
    }

    @Test
    fun `the Clock opens on its alarms list`() {
        val sounds = FakeSounds()
        assertTrue(ToneDefaults(sounds).openClock())
        assertEquals(listOf("openClock"), sounds.calls)
    }

    // Opening the Clock

    @Test
    fun `the alarms screen comes first, and the clock apps are not even looked up when it opens`() {
        val looked = mutableListOf<String>()
        val started = mutableListOf<String>()
        assertTrue(openFirstClock("alarms", launchOf = { looked += it; "launch $it" }) { started += it; true })
        assertEquals(listOf("alarms"), started)
        assertEquals(emptyList<String>(), looked)
    }

    @Test
    fun `an alarms screen that refuses Lyrify falls back to Samsung Clock`() {
        // A clock app that guards its alarms screen: starting it throws, which reads as not started.
        val started = mutableListOf<String>()
        assertTrue(openFirstClock("alarms", launchOf = { "launch $it" }) { started += it; it != "alarms" })
        assertEquals(listOf("alarms", "launch com.sec.android.app.clockpackage"), started)
    }

    @Test
    fun `without an alarms screen, Samsung Clock is tried before Google Clock`() {
        val started = mutableListOf<String>()
        assertFalse(openFirstClock(null, launchOf = { "launch $it" }) { started += it; false })
        assertEquals(listOf("launch com.sec.android.app.clockpackage", "launch com.google.android.deskclock"), started)
    }

    @Test
    fun `a clock app that is not installed is skipped`() {
        val started = mutableListOf<String>()
        val launch = { pkg: String -> if (pkg == "com.google.android.deskclock") "launch $pkg" else null }
        assertTrue(openFirstClock(null, launchOf = launch) { started += it; true })
        assertEquals(listOf("launch com.google.android.deskclock"), started)
    }

    @Test
    fun `with no clock app at all, nothing starts and the screen is told`() {
        val started = mutableListOf<String>()
        assertFalse(openFirstClock<String>(null, launchOf = { null }) { started += it; true })
        assertEquals(emptyList<String>(), started)
    }

    // Which defaults a tone is

    @Test
    fun `a tone shows a pill for each default that is its file`() {
        val sounds = FakeSounds().apply {
            defaults[ToneKind.RINGTONE] = "content://0@media/external_primary/audio/media/41"
            defaults[ToneKind.ALARM] = "content://media/external/audio/media/41?title=Paper%20Lanterns&canonical=1"
            defaults[ToneKind.NOTIFICATION] = "content://media/internal/audio/media/41"
        }
        val active = ToneDefaults(sounds).active()
        assertEquals(setOf(ToneKind.RINGTONE, ToneKind.ALARM), active.of(ringtone))
        assertEquals(emptySet<ToneKind>(), active.of(alarm))
    }

    @Test
    fun `a sound's MediaStore id comes from its uri, for shared storage only`() {
        assertEquals(41L, mediaIdOf("content://media/external_primary/audio/media/41"))
        assertEquals(41L, mediaIdOf("content://10@media/external/audio/media/41?canonical=1"))
        assertEquals(7L, mediaIdOf("content://media/1a2b-3c4d/audio/media/7#x"))
        assertNull(mediaIdOf("content://media/internal/audio/media/41"))
        assertNull(mediaIdOf("content://settings/system/ringtone"))
        assertNull(mediaIdOf("content://media/external/audio/media/notanumber"))
        assertNull(mediaIdOf("file:///storage/emulated/0/Ringtones/x.m4a"))
        assertNull(mediaIdOf(null))
    }
}
