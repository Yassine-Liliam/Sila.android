package app.silati.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The backend session token, encrypted at rest.
 *
 * The token is the app's only credential — it is exactly what the browser holds in its
 * Auth.js cookie — so it never goes into plain `SharedPreferences`. It is sealed with an
 * AES-256-GCM key that lives in the **Android Keystore**, so the key material never enters
 * app memory and cannot be read off the device, even rooted.
 *
 * Written by hand rather than with `androidx.security:security-crypto`: that library is
 * deprecated, and this is ~40 lines of platform API. Same construction the web app uses for
 * Instagram tokens (`lib/crypto.ts`, AES-256-GCM), for the same reason — GCM's auth tag
 * makes tampering fail loudly instead of silently decrypting to garbage.
 *
 * Stored as `base64(iv):base64(ciphertext||tag)`.
 */
class TokenStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return runCatching {
            val (ivPart, dataPart) = stored.split(SEPARATOR, limit = 2).let { it[0] to it[1] }
            val iv = Base64.decode(ivPart, Base64.NO_WRAP)
            val bytes = Base64.decode(dataPart, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(bytes))
        }.getOrElse {
            // Unreadable rather than absent: a restored backup (Keystore keys don't travel
            // between devices), a cleared key, or tampering. Drop it and treat the user as
            // signed out — the worst case is one extra sign-in.
            clear()
            null
        }
    }

    fun write(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(token.toByteArray())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            SEPARATOR +
            Base64.encodeToString(sealed, Base64.NO_WRAP)
        prefs.edit().putString(KEY_TOKEN, encoded).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    /** The Keystore key for this install, created on first use. */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // No setUserAuthenticationRequired: the app must be able to restore a
                    // session on launch without a biometric prompt. Device lock still gates
                    // access to the app itself.
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "silati.session"
        const val KEY_TOKEN = "session_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "silati.session.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
