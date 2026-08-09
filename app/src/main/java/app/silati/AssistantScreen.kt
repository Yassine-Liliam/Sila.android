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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.silati.data.ChatItem
import app.silati.data.ChatMessage
import app.silati.data.ChatRepository
import app.silati.data.SessionError
import app.silati.data.chatItems
import kotlinx.coroutines.launch

/**
 * The assistant — the app's home screen and the point of the whole thing.
 *
 * Conversation state is hoisted to the caller so switching drawer destinations and coming
 * back doesn't wipe it. It is still ephemeral: nothing is persisted, on this device or the
 * server, so a fresh launch starts fresh. That is deliberate (see the repo README) — the
 * assistant is a faster way to do a task, not a companion that remembers you.
 *
 * ponytail: no image attachments yet, so `set_product_image` can't be driven from the phone.
 * The wire format already carries them (content blocks are raw JSON) — it needs a picker and
 * base64 encoding, not a protocol change.
 */
@Composable
fun AssistantScreen(
    messages: List<ChatMessage>,
    onMessagesChange: (List<ChatMessage>) -> Unit,
    chats: ChatRepository,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Resolved here, not in the coroutine: stringResource is a composable call.
    val offlineText = stringResource(R.string.sign_in_offline)
    val failedText = stringResource(R.string.assistant_failed)

    val items = remember(messages) { chatItems(messages) }

    // Keep the newest turn in view as the conversation grows.
    LaunchedEffect(items.size, busy) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }

    val send: () -> Unit = {
        val text = input.trim()
        if (text.isNotEmpty() && !busy) {
            // Show the owner's turn immediately; the server echoes it back in the reply.
            val pending = messages + ChatMessage.user(text)
            onMessagesChange(pending)
            input = ""
            error = null
            busy = true
            scope.launch {
                runCatching { chats.send(pending) }
                    .onSuccess { onMessagesChange(it) }
                    .onFailure { failure ->
                        // Keep the optimistic turn on screen — the message is still there and
                        // retrying should not mean retyping it.
                        when (failure) {
                            // The token is already cleared; the whole app must go back to
                            // sign-in rather than leave a dead screen up.
                            is SessionError.SignedOut -> onSignedOut()
                            is SessionError.Offline -> error = offlineText
                            // Carries the backend's own wording — including the rate-limit
                            // message, which is already written for the owner to read.
                            is SessionError.Failed -> error = failure.detail ?: failedText
                            else -> error = failedText
                        }
                    }
                busy = false
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (items.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items) { item ->
                        when (item) {
                            is ChatItem.Said -> Bubble(item)
                            is ChatItem.ToolRun -> ToolLine(item.name)
                        }
                    }
                }
            }
        }

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            onSend = send,
            busy = busy,
        )
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The launcher icon as a tile — same mark and same background colour, so the
            // empty screen is recognisably the app rather than a bare sentence.
            Surface(
                color = colorResource(R.color.ic_launcher_background),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_glyph),
                    contentDescription = null,
                    // Unspecified keeps the vector's own white; the tile behind it is dark
                    // whatever the theme, so a theme tint would be wrong here.
                    tint = Color.Unspecified,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.assistant_empty),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.assistant_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun Bubble(said: ChatItem.Said) {
    val owner = said.fromOwner
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (owner) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (owner) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (owner) 16.dp else 4.dp,
                bottomEnd = if (owner) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = said.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (owner) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** A tool the assistant ran. Muted and unobtrusive — the AI is an employee, not a mascot. */
@Composable
private fun ToolLine(name: String) {
    Text(
        text = stringResource(R.string.assistant_tool, name),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    busy: Boolean,
) {
    // Transparent rather than a tonally-raised bar: the composer sits at the bottom of the
    // screen with nothing scrolling under it, so the tint separated it from nothing.
    Surface(color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.assistant_hint)) },
                enabled = !busy,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.size(8.dp))
            IconButton(
                onClick = onSend,
                enabled = !busy && value.isNotBlank(),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.assistant_send),
                    )
                }
            }
        }
    }
}
