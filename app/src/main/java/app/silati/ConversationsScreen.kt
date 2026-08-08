package app.silati

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.silati.data.ConversationRepository
import app.silati.data.ConversationSummary
import app.silati.data.ThreadMessage
import app.silati.ui.PagedList
import app.silati.ui.StatusChip
import app.silati.ui.Tone

/**
 * Instagram DM threads.
 *
 * Read-only in Phase 5: the pause / resume toggle — the human-takeover control that matters
 * for Meta App Review — is Phase 6. Until then the assistant can't toggle it either, so it
 * stays a web-only action.
 *
 * ponytail: rows are ordered by when the thread started, not by last activity, and the
 * preview comes from a bounded window of recent messages. That's the backend's shape (it
 * has no `Conversation.lastMessageAt`), not something the client can fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    conversations: ConversationRepository,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<ConversationSummary?>(null) }

    PagedList(
        filter = "",
        load = { cursor, _ -> conversations.page(cursor) },
        itemKey = { it.id },
        // Covers both "no Instagram connected" and "connected, nobody has written yet" —
        // the action is the same either way, and connecting is a web-only flow.
        emptyText = stringResource(R.string.conversations_empty),
        onSignedOut = onSignedOut,
        modifier = modifier,
    ) { conversation ->
        ConversationRow(conversation) { selected = conversation }
    }

    selected?.let { conversation ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ThreadSheet(conversation, conversations, onSignedOut)
        }
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, onClick: () -> Unit) {
    EntityRow(onClick) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                conversation.lastMessage?.text?.let { text ->
                    Text(
                        // Mark our own replies so a thread reads like a conversation.
                        text = if (conversation.lastMessage.fromBusiness) {
                            stringResource(R.string.conversation_you, text)
                        } else {
                            text
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (conversation.paused) {
                StatusChip(stringResource(R.string.conversation_paused), Tone.Pending)
            }
        }
    }
}

/** The full thread. Fetched on open — the list only carries a one-line preview. */
@Composable
private fun ThreadSheet(
    conversation: ConversationSummary,
    conversations: ConversationRepository,
    onSignedOut: () -> Unit,
) {
    var messages by remember { mutableStateOf<List<ThreadMessage>?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(conversation.id) {
        runCatching { conversations.thread(conversation.id) }
            .onSuccess { messages = it.messages.reversed() } // API is newest-first; read oldest-first
            .onFailure {
                if (it is app.silati.data.SessionError.SignedOut) onSignedOut() else failed = true
            }
    }

    Column(modifier = Modifier.heightIn(min = 200.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (conversation.paused) {
                StatusChip(stringResource(R.string.conversation_paused), Tone.Pending)
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            failed -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.list_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            messages == null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages!!, key = { it.id }) { MessageBubble(it) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MessageBubble(message: ThreadMessage) {
    val fromUs = message.fromBusiness
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUs) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (fromUs) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Text(
                // Non-text events arrive as derived markers like "[photo: …]" — the
                // transcript is verbatim for text and derived-once for media.
                text = message.text.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
