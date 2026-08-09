package app.silati

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.silati.data.PushRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives pushes from FCM.
 *
 * Note what this does *not* handle: when the app is backgrounded or dead, a message carrying
 * a `notification` payload is drawn by the system itself and [onMessageReceived] is never
 * called. This class is therefore only doing work in the foreground case — but it has to
 * exist, or a notification arriving while the owner is looking at the app would vanish.
 */
class SilatiMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        val title = notification.title ?: getString(R.string.app_name)
        val body = notification.body.orEmpty()
        // Where the app should land when tapped, sent as data by lib/push.ts.
        val destination = message.data[EXTRA_DESTINATION]

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            destination?.let { putExtra(EXTRA_DESTINATION, it) }
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val built = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        // The permission can be revoked at any time on Android 13+; posting without it
        // throws. Nothing to do but skip — the notification simply doesn't appear.
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return

        // Collapse key as the tag: a burst from one conversation replaces itself in the tray
        // instead of stacking six entries.
        NotificationManagerCompat.from(this)
            .notify(message.collapseKey ?: destination ?: "silati", 0, built)
    }

    /**
     * FCM rotated this device's token, so the address the backend holds is now stale.
     * Re-register immediately — the app might not be opened again for days.
     */
    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { PushRepository(applicationContext).register() }
        }
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
    }
}
