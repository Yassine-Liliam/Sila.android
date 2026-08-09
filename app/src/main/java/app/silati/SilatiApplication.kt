package app.silati

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/** The notification channel id, matched by the backend's FCM payload (`lib/push.ts`). */
const val NOTIFICATION_CHANNEL_ID = "silati"

/**
 * Exists for one reason: to create the notification channel before anything can post to it.
 *
 * Android 8+ silently drops a notification posted to a channel that doesn't exist, and a
 * push can arrive while the app is dead — long before MainActivity would have run. The
 * Application class is the only thing guaranteed to be constructed first.
 */
class SilatiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // HIGH so a new order can surface as a heads-up notification: it needs the
                // owner to do something, and the app exists for exactly this moment.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
