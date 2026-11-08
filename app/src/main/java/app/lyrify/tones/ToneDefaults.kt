package app.lyrify.tones

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.net.toUri

/**
 * The phone's default sounds and the screens around them: the system in the app ([AndroidSystemSounds]), a fake in
 * tests. Only [setDefault] changes anything, and only after the user tapped Set.
 */
interface SystemSounds {
    /** "Modify system settings" is allowed for Lyrify. */
    fun canWrite(): Boolean

    /** Opens Lyrify's "Modify system settings" screen; false when the phone has none. */
    fun openWriteSettings(): Boolean

    /** Makes the tone with MediaStore id [toneId] the default sound of [kind]; true when the setting reads back so. */
    fun setDefault(kind: ToneKind, toneId: Long): Boolean

    /** The default sound of [kind] as stored (a content uri), or null. */
    fun defaultUri(kind: ToneKind): String?

    /** Opens the clock app, on its alarms where it lets Lyrify ([openFirstClock]); false when none opened. */
    fun openClock(): Boolean

    /** Opens the phone's sound settings (a second SIM's ringtone is set there); false when there are none. */
    fun openSoundSettings(): Boolean
}

/** What tapping Set did. */
sealed interface DefaultOutcome {
    /** "Modify system settings" was off: its screen is open. Back in Lyrify, Set is tapped again. */
    data object AskedPermission : DefaultOutcome

    /** "Modify system settings" is off and this phone has no screen to turn it on. */
    data object NoPermissionScreen : DefaultOutcome

    /**
     * The tone is the phone's ringtone or notification sound now. After a ringtone the sheet adds that a second SIM is
     * set in the phone's sound settings ([ToneDefaults.openSoundSettings]): no official way sets it from an app.
     */
    data class Set(val kind: ToneKind) : DefaultOutcome

    /**
     * The tone is the phone's default alarm sound now. Clock apps may keep a sound per alarm (Samsung Clock does), so
     * the sheet says to pick [toneName] there, with an "Open Clock" button ([ToneDefaults.openClock]).
     */
    data class AlarmSet(val toneName: String) : DefaultOutcome

    /** The system did not take the sound (RingtoneManager ignores a uri without an audio MIME type and only logs it). */
    data object NotSet : DefaultOutcome
}

/** The phone's defaults as MediaStore ids, to show which defaults a tone is. */
data class ActiveDefaults(val ids: Map<ToneKind, Long?>) {
    /** The kinds whose default sound is [tone]'s file: its pills. */
    fun of(tone: SavedTone): Set<ToneKind> = ids.filterValues { it == tone.id }.keys
}

/** Sets a tone as a default sound, asking for "Modify system settings" only at that moment. */
class ToneDefaults(private val system: SystemSounds) {
    /** Makes [tone] the default of [kind] (any tone, whatever kind it was saved as). */
    fun set(tone: SavedTone, kind: ToneKind): DefaultOutcome {
        if (!system.canWrite()) return askPermission()
        val taken = try {
            system.setDefault(kind, tone.id)
        } catch (_: SecurityException) {
            // Taken back between the check and the write.
            return askPermission()
        }
        return when {
            !taken -> DefaultOutcome.NotSet
            kind == ToneKind.ALARM -> DefaultOutcome.AlarmSet(tone.name)
            else -> DefaultOutcome.Set(kind)
        }
    }

    /** "Modify system settings" is allowed: Set works at once. */
    fun canWrite(): Boolean = system.canWrite()

    fun openClock(): Boolean = system.openClock()

    fun openSoundSettings(): Boolean = system.openSoundSettings()

    /** The current defaults; reading them needs no permission. */
    fun active(): ActiveDefaults = ActiveDefaults(ToneKind.entries.associateWith { mediaIdOf(system.defaultUri(it)) })

    private fun askPermission(): DefaultOutcome = if (system.openWriteSettings()) DefaultOutcome.AskedPermission else DefaultOutcome.NoPermissionScreen
}

/** The clock apps "Open Clock" falls back to, in this order: Samsung Clock, Google Clock (the manifest's queries name them). */
internal val CLOCK_PACKAGES: List<String> = listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock")

/**
 * "Open Clock", pure: the alarms screen ([showAlarms], null when no app takes it), else the launcher entry of each of
 * [CLOCK_PACKAGES] installed ([launchOf] null when not), until [start] opens one. One that does not open moves on to the
 * next: a clock app may still refuse its alarms screen (it asks for SET_ALARM, which a phone could withhold). False
 * when none opened.
 */
internal fun <T : Any> openFirstClock(showAlarms: T?, launchOf: (String) -> T?, start: (T) -> Boolean): Boolean =
    (sequenceOf(showAlarms) + CLOCK_PACKAGES.asSequence().map(launchOf)).filterNotNull().any(start)

/** `content://[user@]media/<volume>/audio/media/<id>`, with any query or fragment. */
private val MEDIA_AUDIO_URI = Regex("""content://(?:\d+@)?media/([^/?#]+)/audio/media/(\d+)(?:[?#].*)?""")

/**
 * The MediaStore id of the shared-storage audio file [uri] points to; null for anything else, including the internal
 * volume's system sounds (ids of another database). RingtoneManager stores a uri with the user id (`content://0@media`);
 * a sound picker may store `external` instead of `external_primary`, or add a query: the id is the same.
 */
fun mediaIdOf(uri: String?): Long? {
    val match = MEDIA_AUDIO_URI.matchEntire(uri ?: return null) ?: return null
    if (match.groupValues[1] == MediaStore.VOLUME_INTERNAL) return null
    return match.groupValues[2].toLongOrNull()
}

/**
 * [SystemSounds] on RingtoneManager and Settings. Writing a default needs WRITE_SETTINGS declared in the manifest and
 * granted by the user in "Modify system settings" (Settings.System reference). The default is the phone's one: on a
 * dual-SIM phone, the first SIM's or both, as the phone decides.
 */
class AndroidSystemSounds(private val context: Context) : SystemSounds {
    override fun canWrite(): Boolean = Settings.System.canWrite(context)

    /** With the "package:" data uri the Settings reference gives, so the screen opens on Lyrify. */
    override fun openWriteSettings(): Boolean =
        start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri()))

    override fun setDefault(kind: ToneKind, toneId: Long): Boolean {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), toneId)
        RingtoneManager.setActualDefaultRingtoneUri(context, kind.ringtoneType, uri)
        return mediaIdOf(defaultUri(kind)) == toneId
    }

    override fun defaultUri(kind: ToneKind): String? = RingtoneManager.getActualDefaultRingtoneUri(context, kind.ringtoneType)?.toString()

    override fun openClock(): Boolean {
        val pm = context.packageManager
        val alarms = Intent(AlarmClock.ACTION_SHOW_ALARMS).takeIf { it.resolveActivity(pm) != null }
        return openFirstClock(alarms, pm::getLaunchIntentForPackage, ::start)
    }

    override fun openSoundSettings(): Boolean = start(Intent(Settings.ACTION_SOUND_SETTINGS))

    private fun start(intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            // A clock app that guards its alarms screen.
            false
        }
    }
}
