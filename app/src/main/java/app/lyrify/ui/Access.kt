package app.lyrify.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import app.lyrify.LOG_TAG
import app.lyrify.media.NowPlayingListener

/** The two grants Lyrify needs; the setup screen shows until both are on. */
data class Access(val notifications: Boolean, val allFiles: Boolean) {
    val complete: Boolean get() = notifications && allFiles

    companion object {
        fun of(context: Context): Access = Access(hasNotificationAccess(context), Environment.isExternalStorageManager())
    }
}

/** True when the user has switched Lyrify on in Notification access (Device and app notifications). */
fun hasNotificationAccess(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

/** Lyrify's own switch in notification access, else the list of all listeners. */
fun notificationAccessIntents(context: Context): List<Intent> = listOf(
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
        ComponentName(context, NowPlayingListener::class.java).flattenToString(),
    ),
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
)

/** Lyrify's own All files access switch, else the list of apps with that access. */
fun allFilesAccessIntents(context: Context): List<Intent> = listOf(
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(context)),
    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
)

/** App info: the ⋮ menu there has "Allow restricted settings"; Battery has "Never sleeping apps" on Samsung. */
fun appInfoIntents(context: Context): List<Intent> = listOf(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
)

private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

/** Opens the first of [intents] that this device can handle: Settings screens differ between builds. */
fun Context.startFirst(intents: List<Intent>) {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            Log.d(LOG_TAG, "no screen for ${intent.action}: ${e.message}")
        }
    }
}
