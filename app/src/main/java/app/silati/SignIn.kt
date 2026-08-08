package app.silati

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

/**
 * The web app's OAuth **Web** client id — the `aud` the backend will verify against.
 * Not a secret: client ids are public identifiers and ship in the web app's HTML too.
 * The separate Android client (package + SHA-1, registered in Google Cloud) is what lets
 * Google recognise this APK; it has no secret and never appears in code.
 */
private const val WEB_CLIENT_ID =
    "404292140130-h9gqcmchc4s3ucl94gicbh00q215dmdp.apps.googleusercontent.com"

/**
 * Shows the system account picker and returns the Google ID token it issues.
 *
 * Uses [GetGoogleIdOption] rather than `GetSignInWithGoogleOption`: the latter runs a branded
 * sign-in ceremony whose final `CompleteSignInOperation` failed with 28444 ("Developer console
 * is not set up correctly") even with the console configured correctly. This option fetches
 * the same token without that step.
 */
suspend fun signInWithGoogle(context: Context): String {
    val option = GetGoogleIdOption.Builder()
        .setServerClientId(WEB_CLIENT_ID)
        .setFilterByAuthorizedAccounts(false)
        .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = CredentialManager.create(context).getCredential(context, request).credential

    check(
        credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) { "Unexpected credential type: ${credential.type}" }

    return GoogleIdTokenCredential.createFrom(credential.data).idToken
}

@Composable
fun SignInScreen(onSignedIn: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.sign_in_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(48.dp))
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { signInWithGoogle(context) }
                            .onSuccess(onSignedIn)
                            .onFailure { e ->
                                // Dismissing the picker is not an error worth shouting about.
                                if (e !is GetCredentialCancellationException) {
                                    error = e.message
                                        ?: context.getString(R.string.sign_in_failed)
                                }
                            }
                        busy = false
                    }
                },
            ) {
                Text(stringResource(R.string.sign_in_google))
            }
            if (busy) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator()
            }
            error?.let {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
