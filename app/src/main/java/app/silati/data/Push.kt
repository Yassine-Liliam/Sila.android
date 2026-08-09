package app.silati.data

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "SilatiPush"

/**
 * Registering this phone for push notifications.
 *
 * The FCM token is the phone's address, and it is not stable: it rotates on reinstall, on a
 * device restore, and whenever FCM decides to. So [register] runs on every launch rather
 * than once — the backend upserts, so re-registering the same token is free.
 *
 * Failure is never fatal here — push is a convenience and must not keep an owner out of the
 * app — but every branch logs. A silent best-effort call is undebuggable, which is exactly
 * how the first attempt at this went wrong.
 */
class PushRepository(context: Context) {
    private val tokens = TokenStore(context)

    suspend fun register(): Boolean {
        val session = tokens.read()
        if (session == null) {
            Log.w(TAG, "register skipped: no session token stored")
            return false
        }
        val fcmToken = currentFcmToken()
        if (fcmToken == null) {
            Log.w(TAG, "register skipped: Play services returned no FCM token")
            return false
        }
        return runCatching { api.registerDevice(bearer(session), DeviceTokenRequest(fcmToken)) }
            .onSuccess { Log.i(TAG, "registered device with backend") }
            .onFailure { Log.e(TAG, "register call failed", it) }
            .isSuccess
    }

    /**
     * Stop notifications to this device. Called on sign-out, *before* the session token is
     * cleared — the request needs it.
     */
    suspend fun unregister(): Boolean {
        val session = tokens.read() ?: return false
        val fcmToken = currentFcmToken() ?: return false
        return runCatching { api.unregisterDevice(bearer(session), DeviceTokenRequest(fcmToken)) }
            .onFailure { Log.w(TAG, "unregister call failed", it) }
            .isSuccess
    }
}

/**
 * The current FCM token, or null if Play services can't produce one.
 *
 * The usual reasons are environmental rather than code: an emulator image without Google
 * Play, an outdated Play services, or no network at the moment it's asked. The failure
 * carries the reason, so log it rather than collapsing it to null in silence.
 *
 * Wrapped by hand rather than pulling in `kotlinx-coroutines-play-services` for one call.
 */
suspend fun currentFcmToken(): String? = suspendCancellableCoroutine { continuation ->
    FirebaseMessaging.getInstance().token
        .addOnSuccessListener { token ->
            Log.i(TAG, "FCM token acquired (${token.take(12)}…)")
            continuation.resume(token)
        }
        .addOnFailureListener { error ->
            Log.e(TAG, "could not get an FCM token", error)
            continuation.resume(null)
        }
}
