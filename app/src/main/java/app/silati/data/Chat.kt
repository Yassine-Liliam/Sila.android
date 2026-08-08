package app.silati.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One turn in the assistant conversation, in Anthropic's message shape.
 *
 * `content` is deliberately a raw [JsonElement] rather than a modelled union. A turn can hold
 * text, `tool_use`, `tool_result` or image blocks, and the *whole* conversation is posted back
 * on every turn — including the tool calls and their results, which is how the model keeps its
 * place. Modelling those blocks would mean re-serialising them exactly, and any field we
 * failed to model would be silently dropped, corrupting the history. Keeping the raw JSON
 * makes the round trip lossless by construction; the UI reads only the parts it can display.
 */
@Serializable
data class ChatMessage(val role: String, val content: JsonElement) {
    companion object {
        /** A plain text turn from the owner. */
        fun user(text: String) = ChatMessage(
            role = "user",
            content = buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            },
        )
    }
}

@Serializable
data class ChatRequest(val history: List<ChatMessage>)

@Serializable
data class ChatResponse(val messages: List<ChatMessage> = emptyList())

/** Something the assistant screen can draw. */
sealed interface ChatItem {
    data class Said(val fromOwner: Boolean, val text: String) : ChatItem

    /** A tool the assistant ran, shown as one muted line — same as the web. */
    data class ToolRun(val name: String) : ChatItem
}

/**
 * Flattens the wire history into displayable items.
 *
 * Most turns produce one bubble, but two cases need care: an assistant turn can carry text
 * *and* tool calls, and the tool *results* come back as `user`-role turns containing only
 * `tool_result` blocks — those must not render as empty bubbles from the owner.
 */
fun chatItems(messages: List<ChatMessage>): List<ChatItem> = buildList {
    for (message in messages) {
        val fromOwner = message.role == "user"
        when (val content = message.content) {
            is JsonPrimitive -> content.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { add(ChatItem.Said(fromOwner, it)) }

            is JsonArray -> for (block in content) {
                val obj = block as? JsonObject ?: continue
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { add(ChatItem.Said(fromOwner, it)) }

                    "tool_use" -> obj["name"]?.jsonPrimitive?.contentOrNull
                        ?.let { add(ChatItem.ToolRun(it)) }

                    // tool_result and image blocks carry nothing worth showing.
                }
            }

            else -> Unit
        }
    }
}

/**
 * The assistant conversation.
 *
 * Ephemeral by design, matching the web: the client holds the whole history and posts it
 * back each turn; the server persists nothing, so a fresh launch starts a fresh conversation.
 */
class ChatRepository(context: Context) {
    // A separate instance from SessionRepository's is fine — TokenStore is a stateless view
    // over the same SharedPreferences and Keystore entry.
    private val tokens = TokenStore(context)

    /** Sends the conversation and returns the updated one, which replaces local state. */
    suspend fun send(history: List<ChatMessage>): List<ChatMessage> = apiCall(tokens) {
        val token = tokens.read() ?: throw SessionError.SignedOut
        api.chat(bearer(token), ChatRequest(history)).messages
    }
}
