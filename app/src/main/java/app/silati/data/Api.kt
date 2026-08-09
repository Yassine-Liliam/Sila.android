package app.silati.data

import app.silati.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * The mobile API surface on the Silati Worker (routes under `api/mobile`).
 *
 * Note: never write that path with a trailing wildcard in a comment — Kotlin block comments
 * nest, so the slash-star sequence silently opens a nested comment.
 *
 * Only the routes Phase 2 needs are declared; the rest land with the screens that use them.
 * Auth is an explicit `Authorization` header per call rather than an OkHttp interceptor —
 * with two endpoints the interceptor is more machinery than it saves, and sign-in is the one
 * call that must *not* carry a token.
 */
interface SilatiApi {
    /** The only unauthenticated route: Google ID token in, backend session token out. */
    @POST("api/mobile/auth/google")
    suspend fun signInWithGoogle(@Body body: GoogleSignInRequest): AuthResponse

    /** Current user + business. `business == null` means the user has not onboarded. */
    @GET("api/mobile/me")
    suspend fun me(@Header("Authorization") bearer: String): MeResponse

    /**
     * Creates the user's first business. Runs once per account — a second call is a 409,
     * which is the backend's guard against a retry creating two businesses.
     *
     * The AI brief is never sent: the backend derives it from these answers.
     */
    @POST("api/mobile/onboarding")
    suspend fun onboard(
        @Header("Authorization") bearer: String,
        @Body body: Onboarding,
    ): OnboardingResponse

    /** One assistant turn: the whole conversation in, the whole conversation back. */
    @POST("api/mobile/chat")
    suspend fun chat(
        @Header("Authorization") bearer: String,
        @Body body: ChatRequest,
    ): ChatResponse

    // Lists. A null query param is left off the request entirely, so the backend applies
    // its own defaults (limit 50, no filter).

    @GET("api/mobile/products")
    suspend fun products(
        @Header("Authorization") bearer: String,
        @Query("cursor") cursor: String? = null,
        @Query("search") search: String? = null,
    ): Page<Product>

    @GET("api/mobile/clients")
    suspend fun clients(
        @Header("Authorization") bearer: String,
        @Query("cursor") cursor: String? = null,
        @Query("search") search: String? = null,
    ): Page<Client>

    @GET("api/mobile/conversations")
    suspend fun conversations(
        @Header("Authorization") bearer: String,
        @Query("cursor") cursor: String? = null,
    ): Page<ConversationSummary>

    /** One DM thread: the conversation plus a page of its messages, newest first. */
    @GET("api/mobile/conversations/{id}")
    suspend fun conversation(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Query("cursor") cursor: String? = null,
    ): ConversationThread

    @GET("api/mobile/purchases")
    suspend fun purchases(
        @Header("Authorization") bearer: String,
        @Query("cursor") cursor: String? = null,
        @Query("status") status: String? = null,
    ): Page<Purchase>

    @GET("api/mobile/deliveries")
    suspend fun deliveries(
        @Header("Authorization") bearer: String,
        @Query("cursor") cursor: String? = null,
        @Query("status") status: String? = null,
    ): Page<Delivery>

    // Write actions. Each returns the updated entity, so the UI never has to guess what the
    // server did — confirming an order can also create a delivery, for instance.

    /** Owner-gated. Also creates the delivery when the business delivers and there's an address. */
    @POST("api/mobile/purchases/{id}/confirm")
    suspend fun confirmPurchase(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
    ): PurchaseEnvelope

    /** Cancels the order and, if it has one, its pending or in-transit delivery. */
    @POST("api/mobile/purchases/{id}/cancel")
    suspend fun cancelPurchase(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
    ): PurchaseEnvelope

    /** Human takeover: paused means the webhook keeps recording but the AI stops replying. */
    @PATCH("api/mobile/conversations/{id}/paused")
    suspend fun setConversationPaused(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Body body: PausedRequest,
    ): PausedResponse

    @PATCH("api/mobile/deliveries/{id}")
    suspend fun updateDelivery(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Body body: DeliveryUpdate,
    ): DeliveryEnvelope

    @POST("api/mobile/products")
    suspend fun createProduct(
        @Header("Authorization") bearer: String,
        @Body body: ProductInput,
    ): ProductEnvelope

    @PATCH("api/mobile/products/{id}")
    suspend fun updateProduct(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Body body: ProductInput,
    ): ProductEnvelope

    @POST("api/mobile/clients")
    suspend fun createClient(
        @Header("Authorization") bearer: String,
        @Body body: ClientInput,
    ): ClientEnvelope

    @PATCH("api/mobile/clients/{id}")
    suspend fun updateClient(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Body body: ClientInput,
    ): ClientEnvelope

    /** Register this device's FCM token. Safe to repeat — the backend upserts. */
    @POST("api/mobile/devices")
    suspend fun registerDevice(
        @Header("Authorization") bearer: String,
        @Body body: DeviceTokenRequest,
    ): DeviceRegistration

    /** DELETE with a body — hence @HTTP rather than @DELETE, which forbids one. */
    @HTTP(method = "DELETE", path = "api/mobile/devices", hasBody = true)
    suspend fun unregisterDevice(
        @Header("Authorization") bearer: String,
        @Body body: DeviceTokenRequest,
    ): DeviceRegistration

    @GET("api/mobile/settings")
    suspend fun settings(@Header("Authorization") bearer: String): SettingsResponse

    /**
     * A one-time URL that signs this owner into the browser and drops them straight into the
     * Instagram OAuth flow. Valid for about a minute and single-use — fetch it when the
     * Connect button is tapped, never earlier.
     */
    @POST("api/mobile/instagram/connect-code")
    suspend fun instagramConnectUrl(
        @Header("Authorization") bearer: String,
    ): InstagramConnectUrl

    /** The backend re-derives the AI brief from the answers; it is never sent from here. */
    @PATCH("api/mobile/settings")
    suspend fun updateSettings(
        @Header("Authorization") bearer: String,
        @Body body: SettingsPatch,
    ): SettingsPatchResponse

    /** Body must carry `confirm: "DELETE"` — the API refuses a bare irreversible DELETE. */
    @HTTP(method = "DELETE", path = "api/mobile/account", hasBody = true)
    suspend fun deleteAccount(
        @Header("Authorization") bearer: String,
        @Body body: DeleteAccountRequest,
    ): DeleteAccountResponse
}

@Serializable
data class DeviceTokenRequest(val token: String)

@Serializable
data class DeviceRegistration(
    val registered: Boolean = false,
    val unregistered: Boolean = false,
)

@Serializable
data class InstagramConnectUrl(val url: String, val expiresAt: String? = null)

/** Ignored in practice — the app re-reads /me afterwards, which is the shape it uses. */
@Serializable
data class OnboardingResponse(val business: ApiBusiness? = null)

@Serializable
data class SettingsPatchResponse(val user: ApiUser? = null)

@Serializable
data class DeleteAccountResponse(val deleted: Boolean = false)

@Serializable
data class ProductEnvelope(val product: Product)

@Serializable
data class ClientEnvelope(val client: Client)

/**
 * Product create/update body.
 *
 * Every field is nullable and the Json is configured with `explicitNulls = false`, so a null
 * is **omitted from the request** — which the backend reads as "leave this alone". That's
 * what makes one type serve both POST and PATCH.
 *
 * To *clear* a field, send an empty string rather than null: the backend turns a
 * present-but-empty value into null. `stock = ""` therefore means "stop tracking stock",
 * which is different from `stock = "0"` (tracked, none left).
 */
@Serializable
data class ProductInput(
    val name: String? = null,
    val price: String? = null,
    val description: String? = null,
    val stock: String? = null,
    val currency: String? = null,
    val active: Boolean? = null,
)

/** Same omit-vs-empty rule as [ProductInput]. */
@Serializable
data class ClientInput(
    val name: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val city: String? = null,
    val email: String? = null,
    val notes: String? = null,
)

@Serializable
data class PurchaseEnvelope(val purchase: Purchase)

@Serializable
data class DeliveryEnvelope(val delivery: Delivery)

@Serializable
data class PausedRequest(val paused: Boolean)

@Serializable
data class PausedResponse(val id: String, val paused: Boolean)

@Serializable
data class DeliveryUpdate(val status: String)

// ── Wire types ──────────────────────────────────────────────────────────────
// Every optional field carries a default so a backend that adds or omits a key can't crash
// the app (paired with ignoreUnknownKeys below).

@Serializable
data class GoogleSignInRequest(val idToken: String)

@Serializable
data class AuthResponse(
    val sessionToken: String,
    val expires: String,
    val user: ApiUser,
    val business: AuthBusiness? = null,
)

@Serializable
data class AuthBusiness(val id: String, val onboarded: Boolean = true)

@Serializable
data class ApiUser(
    val id: String,
    val name: String? = null,
    val email: String,
    val image: String? = null,
)

@Serializable
data class MeResponse(
    val user: ApiUser,
    val business: ApiBusiness? = null,
    val onboarded: Boolean = false,
    val instagram: InstagramStatus = InstagramStatus(),
)

@Serializable
data class ApiBusiness(
    val id: String,
    /** Optional: older backends don't send it. Falls back to the email in the UI. */
    val name: String? = null,
    val businessProfile: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class InstagramStatus(
    val connected: Boolean = false,
    val username: String? = null,
    @SerialName("connectedAt") val connectedAt: String? = null,
    @SerialName("expiresAt") val expiresAt: String? = null,
)

/** The error body every mobile route returns on failure. */
@Serializable
data class ApiErrorBody(val error: ApiErrorDetail? = null)

@Serializable
data class ApiErrorDetail(val code: String = "", val message: String = "")

// ── Client ──────────────────────────────────────────────────────────────────

private val json = Json {
    // The backend may add fields at any time; an unknown key must never be fatal.
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * One Retrofit instance for the process. Retrofit is thread-safe and holds the connection
 * pool, so rebuilding it per call would throw away every kept-alive connection.
 *
 * The read timeout is the reason this configures OkHttp at all. An assistant turn runs
 * Sonnet plus a tool loop server-side and legitimately takes tens of seconds; OkHttp's
 * 10-second default would abort a perfectly healthy request. Connect stays short — failing
 * to reach the Worker at all should be reported quickly.
 */
val api: SilatiApi by lazy {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS) // product images are base64 in the body
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(SilatiApi::class.java)
}

/** Header value for an authenticated call. */
fun bearer(token: String) = "Bearer $token"

/** Parses the `{ error: { code, message } }` body a failed call carries, if any. */
fun parseApiError(body: String?): ApiErrorDetail? =
    body?.takeIf { it.isNotBlank() }?.let {
        runCatching { json.decodeFromString<ApiErrorBody>(it).error }.getOrNull()
    }
