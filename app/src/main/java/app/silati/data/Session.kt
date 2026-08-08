package app.silati.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

/**
 * What the app knows about the signed-in owner. Everything the UI needs to decide which
 * screen to show.
 */
data class Session(val user: ApiUser, val business: ApiBusiness?) {
    val onboarded get() = business != null

    /** What to show in the app bar: the business name, falling back to the person. */
    val displayName: String get() = business?.name ?: user.name ?: user.email
}

/** Why a call failed — each maps to a different thing to tell the user. */
sealed class SessionError : Exception() {
    /** No usable session; the user must sign in again. Any stored token has been cleared. */
    data object SignedOut : SessionError()

    /** The Worker was unreachable. Worth a retry; the stored token is still good. */
    data object Offline : SessionError()

    /** Anything else — carries the backend's message when it sent one. */
    data class Failed(val detail: String?) : SessionError()
}

/**
 * Runs an API call off the main thread and maps transport failures onto [SessionError].
 *
 * A 401 is the one that must clear the token: the session was revoked or expired
 * server-side, so retrying with it can only fail again. Everything else keeps the token —
 * a 429 from the assistant rate limit in particular is a normal answer, not a bad session,
 * and its message is already written for the owner to read.
 */
internal suspend fun <T> apiCall(tokens: TokenStore, block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: HttpException) {
            if (e.code() == 401) {
                tokens.clear()
                throw SessionError.SignedOut
            }
            throw SessionError.Failed(
                parseApiError(e.response()?.errorBody()?.string())?.message
            )
        } catch (_: IOException) {
            // Includes SocketTimeoutException — see the timeouts on the OkHttp client.
            throw SessionError.Offline
        }
    }

/**
 * Owns the session token and turns it into a [Session].
 *
 * Deliberately not a ViewModel: there is one of these per process, it holds no UI state, and
 * the app has no dependency-injection framework to justify yet.
 * ponytail: no in-memory cache of the last [Session] — `restore()` hits the network on every
 * cold start (and on rotation). Add a cache when a screen makes that visible.
 */
class SessionRepository(context: Context) {
    private val tokens = TokenStore(context)

    /** True when a token is stored — lets the UI show a loader instead of the sign-in screen. */
    fun hasToken(): Boolean = tokens.read() != null

    /** Exchange a Google ID token for a backend session, then load the owner. */
    suspend fun signIn(googleIdToken: String): Session = apiCall(tokens) {
        val auth = api.signInWithGoogle(GoogleSignInRequest(googleIdToken))
        tokens.write(auth.sessionToken)
        // Not built from the sign-in response: /me is the one shape the rest of the app uses,
        // and it carries the business name and Instagram status the auth route doesn't.
        api.me(bearer(auth.sessionToken)).toSession()
    }

    /** Re-establish the session on launch from the stored token. */
    suspend fun restore(): Session {
        val token = tokens.read() ?: throw SessionError.SignedOut
        return apiCall(tokens) { api.me(bearer(token)).toSession() }
    }

    /** Forget the session locally. */
    fun signOut() = tokens.clear()

    private fun MeResponse.toSession() = Session(user = user, business = business)
}
