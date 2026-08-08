package app.silati.data

import android.content.Context
import kotlinx.serialization.Serializable

// The read models behind Clients, Conversations, Purchases and Deliveries.
//
// Money is always a String, straight from the database Decimal — see the note on Product.
// Timestamps are ISO-8601 strings and stay that way: nothing here formats dates yet, and
// parsing them into java.time only to print them back would be work with no reader.
// ponytail: format timestamps for display when a screen actually shows one.

// ── Clients ─────────────────────────────────────────────────────────────────

@Serializable
data class Client(
    val id: String,
    val name: String,
    val phone: String? = null,
    val address: String? = null,
    val city: String? = null,
    val email: String? = null,
    val notes: String? = null,
    /** Set when this client came from an Instagram conversation. */
    val conversationId: String? = null,
    val createdAt: String? = null,
) {
    /** Whatever contact detail is worth showing on one line. */
    val subtitle: String? get() = listOfNotNull(phone, city).joinToString(" · ").ifBlank { null }
}

class ClientRepository(context: Context) {
    private val tokens = TokenStore(context)

    suspend fun page(cursor: String? = null, search: String? = null): Page<Client> =
        apiCall(tokens) {
            val token = tokens.read() ?: throw SessionError.SignedOut
            api.clients(bearer(token), cursor, search?.takeIf { it.isNotBlank() })
        }
}

// ── Conversations ───────────────────────────────────────────────────────────

@Serializable
data class ConversationSummary(
    val id: String,
    val customerIgsid: String,
    /** true = a human took over; the webhook still records but the AI stops replying. */
    val paused: Boolean = false,
    val createdAt: String? = null,
    val client: ClientRef? = null,
    val lastMessage: LastMessage? = null,
) {
    /** The client's name once they've ordered; before that, only their Instagram id. */
    val title: String get() = client?.name ?: customerIgsid
}

@Serializable
data class ClientRef(val id: String, val name: String)

@Serializable
data class LastMessage(
    val text: String? = null,
    /** true = sent by the business (the AI's reply, or the owner's own). */
    val fromBusiness: Boolean = false,
    val sentAt: String? = null,
)

@Serializable
data class ConversationThread(
    val conversation: ConversationSummary,
    val messages: List<ThreadMessage> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class ThreadMessage(
    val id: String,
    val text: String? = null,
    val fromBusiness: Boolean = false,
    val sentAt: String? = null,
)

class ConversationRepository(context: Context) {
    private val tokens = TokenStore(context)

    suspend fun page(cursor: String? = null): Page<ConversationSummary> = apiCall(tokens) {
        val token = tokens.read() ?: throw SessionError.SignedOut
        api.conversations(bearer(token), cursor)
    }

    /** One thread, newest messages first. */
    suspend fun thread(id: String, cursor: String? = null): ConversationThread = apiCall(tokens) {
        val token = tokens.read() ?: throw SessionError.SignedOut
        api.conversation(bearer(token), id, cursor)
    }
}

// ── Purchases ───────────────────────────────────────────────────────────────

@Serializable
data class Purchase(
    val id: String,
    /** pending | confirmed | cancelled */
    val status: String,
    /** manual | ai_instagram | ai_chat */
    val source: String = "manual",
    val total: String,
    val currency: String = "MAD",
    val notes: String? = null,
    val createdAt: String? = null,
    val client: ClientRef? = null,
    val items: List<PurchaseItem> = emptyList(),
    val delivery: DeliveryRef? = null,
) {
    val displayTotal: String get() = "$total $currency"
}

@Serializable
data class PurchaseItem(
    val id: String,
    val productId: String? = null,
    /** Snapshot taken when the order was placed — survives catalog edits. */
    val productName: String,
    val quantity: Int,
    val unitPrice: String,
)

@Serializable
data class DeliveryRef(val id: String, val status: String)

class PurchaseRepository(context: Context) {
    private val tokens = TokenStore(context)

    /** @param status blank for all; otherwise pending / confirmed / cancelled. */
    suspend fun page(cursor: String? = null, status: String? = null): Page<Purchase> =
        apiCall(tokens) {
            val token = tokens.read() ?: throw SessionError.SignedOut
            api.purchases(bearer(token), cursor, status?.takeIf { it.isNotBlank() })
        }
}

// ── Deliveries ──────────────────────────────────────────────────────────────

@Serializable
data class Delivery(
    val id: String,
    /** pending | in_transit | delivered | failed | cancelled */
    val status: String,
    val address: String,
    val city: String? = null,
    val phone: String? = null,
    val scheduledAt: String? = null,
    val deliveredAt: String? = null,
    val notes: String? = null,
    val createdAt: String? = null,
    val purchaseId: String? = null,
    val total: String? = null,
    val currency: String = "MAD",
    val client: ClientRef? = null,
) {
    val where: String get() = listOfNotNull(address, city).joinToString(", ")
}

class DeliveryRepository(context: Context) {
    private val tokens = TokenStore(context)

    suspend fun page(cursor: String? = null, status: String? = null): Page<Delivery> =
        apiCall(tokens) {
            val token = tokens.read() ?: throw SessionError.SignedOut
            api.deliveries(bearer(token), cursor, status?.takeIf { it.isNotBlank() })
        }
}
