package app.silati.data

import android.content.Context
import kotlinx.serialization.Serializable

/**
 * The onboarding answers — the structured source of truth for a business.
 *
 * The backend never accepts the AI brief text directly: it merges whatever this object
 * carries into the stored answers, re-runs `sanitize()`, and **re-derives** `businessProfile`
 * from the result. That's what stops the answers and the text the AIs read from drifting.
 *
 * Every field is nullable and the Json omits nulls, so a partial patch means "change only
 * these". Sending the whole object back would work too, but a partial keeps the intent clear.
 */
@Serializable
data class Onboarding(
    val userName: String? = null,
    val businessName: String? = null,
    val sells: String? = null,
    val story: String? = null,
    val language: String? = null,
    val aiLanguage: String? = null,
    val tone: String? = null,
    val rules: List<String>? = null,
    val delivers: Boolean? = null,
    val deliveryAreas: List<String>? = null,
    val pickupAddress: String? = null,
    val payment: List<String>? = null,
    val paymentOther: String? = null,
    val phone: String? = null,
    val confirmInfo: List<String>? = null,
    val confirmOther: String? = null,
    val deliveryInstructions: String? = null,
    /** "none" or "days". */
    val returnPolicy: String? = null,
    val returnDays: Int? = null,
    val otherPolicies: String? = null,
)

@Serializable
data class SettingsResponse(
    val user: ApiUser,
    val business: SettingsBusiness,
    val instagram: InstagramStatus = InstagramStatus(),
)

@Serializable
data class SettingsBusiness(
    val id: String,
    val businessProfile: String? = null,
    val onboarding: Onboarding? = null,
)

@Serializable
data class SettingsPatch(
    val name: String? = null,
    val answers: Onboarding? = null,
)

@Serializable
data class DeleteAccountRequest(val confirm: String)

/**
 * Option lists mirrored from the web wizard (`app/onboarding/compose.ts`).
 *
 * Duplicated on purpose: the repos share no code, and these are short and stable. The
 * backend doesn't validate against them — it only length-caps — so a drift here degrades to
 * a different set of suggestions, never a rejected save.
 */
object Options {
    val LANGUAGES = listOf("English", "French", "Arabic")
    val TONES = listOf("professional", "casual", "friendly")
    val RULES = listOf(
        "Always stay polite and patient",
        "Confirm order details before closing",
        "Never promise discounts or prices not listed",
        "Escalate complaints or refunds to a human",
        "Only answer questions about our products",
        "Never share personal opinions",
    )
    val PAYMENTS = listOf("Cash on delivery", "Bank transfer", "Cash")
    val CONFIRM_INFO = listOf("Full name", "City", "Address", "Phone number", "Email address")
}

class SettingsRepository(context: Context) {
    private val tokens = TokenStore(context)

    suspend fun load(): SettingsResponse = apiCall(tokens) {
        val token = tokens.read() ?: throw SessionError.SignedOut
        api.settings(bearer(token))
    }

    /** @param name null leaves the display name alone; "" clears it. */
    suspend fun save(name: String? = null, answers: Onboarding? = null): SettingsResponse =
        apiCall(tokens) {
            val token = tokens.read() ?: throw SessionError.SignedOut
            api.updateSettings(bearer(token), SettingsPatch(name, answers))
            // The PATCH returns only user + business; re-read so the Instagram block and the
            // freshly re-derived profile text come back in one consistent shape.
            api.settings(bearer(token))
        }

    /**
     * A single-use URL that opens the Instagram OAuth flow already signed in.
     *
     * Fetched at the moment the owner taps Connect, never cached: it dies after ~60 seconds
     * and after one use, which is what makes it safe to put in a URL at all.
     */
    suspend fun instagramConnectUrl(): String = apiCall(tokens) {
        val token = tokens.read() ?: throw SessionError.SignedOut
        api.instagramConnectUrl(bearer(token)).url
    }

    /**
     * Irreversible. Cascades to businesses, the Instagram account, messages, conversations,
     * clients, products, purchases and deliveries — and to this device's session, since the
     * Session rows go with the User.
     */
    suspend fun deleteAccount() = apiCall(tokens) {
        val token = tokens.read() ?: throw SessionError.SignedOut
        api.deleteAccount(bearer(token), DeleteAccountRequest("DELETE"))
        tokens.clear()
    }
}
