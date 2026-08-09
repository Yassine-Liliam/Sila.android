package app.silati.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.silati.R
import app.silati.data.Page
import app.silati.data.SessionError
import kotlinx.coroutines.delay

/**
 * The list machinery every entity screen needs: first page, cursor pagination as the end
 * comes into view, debounce on a changing filter, and the loading / empty / error / retry
 * states around it.
 *
 * Extracted once Products stopped being the only list — five screens sharing one of these
 * beats five hand-rolled copies drifting apart.
 *
 * @param filter reload key. A change resets the list; a non-empty one is debounced, so
 *   typing a search doesn't fire a request per keystroke.
 * @param reloadKey bump to re-fetch from the first page after a write action. Reloading
 *   rather than patching the row in place is deliberate: confirming an order can also create
 *   a delivery and flip a status, and re-reading is the only way the list is certainly right.
 *   ponytail: it costs the scroll position — fine for a shop's list lengths, revisit if not.
 */
@Composable
fun <T : Any> PagedList(
    filter: String,
    load: suspend (cursor: String?, filter: String) -> Page<T>,
    itemKey: (T) -> String,
    emptyText: String,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    emptyTextWhenFiltered: String? = null,
    reloadKey: Int = 0,
    row: @Composable (T) -> Unit,
) {
    var items by remember { mutableStateOf<List<T>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var reachedEnd by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val offlineText = stringResource(R.string.sign_in_offline)
    val failedText = stringResource(R.string.list_failed)

    fun report(failure: Throwable) {
        when (failure) {
            is SessionError.SignedOut -> onSignedOut()
            is SessionError.Offline -> error = offlineText
            is SessionError.Failed -> error = failure.detail ?: failedText
            else -> error = failedText
        }
    }

    LaunchedEffect(filter, reloads, reloadKey) {
        if (filter.isNotEmpty()) delay(300)
        loading = true
        error = null
        runCatching { load(null, filter) }
            .onSuccess {
                items = it.items
                cursor = it.nextCursor
                reachedEnd = it.nextCursor == null
            }
            .onFailure(::report)
        loading = false
    }

    LaunchedEffect(listState, items, reachedEnd) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                val next = cursor
                if (lastVisible != null && next != null && !reachedEnd && !loading &&
                    lastVisible >= items.lastIndex - 3
                ) {
                    loading = true
                    runCatching { load(next, filter) }
                        .onSuccess { page ->
                            // De-duplicate: a row created between fetches shifts the cursor
                            // window and can repeat an id.
                            val seen = items.mapTo(HashSet(), itemKey)
                            items = items + page.items.filterNot { itemKey(it) in seen }
                            cursor = page.nextCursor
                            reachedEnd = page.nextCursor == null
                        }
                        .onFailure(::report)
                    loading = false
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            items.isEmpty() && loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            items.isEmpty() && error != null -> ListError(error!!) { reloads++ }

            items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (filter.isNotBlank() && emptyTextWhenFiltered != null) {
                        emptyTextWhenFiltered
                    } else {
                        emptyText
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Keyed + animateItem: a write re-fetches the whole first page, so rows that
                // moved (or a confirmed order leaving a filtered list) slide instead of
                // snapping to their new place.
                items(items, key = itemKey) { Box(Modifier.animateItem()) { row(it) } }
                if (!reachedEnd) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }

        // A failure with rows already on screen shouldn't blank them out.
        if (items.isNotEmpty() && error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ListError(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** How a status reads at a glance. Mirrors the web's badge palette. */
enum class Tone { Pending, Positive, Negative, Info, Neutral }

/**
 * A small status pill. Colours are literal rather than theme roles: order status carries
 * meaning (pending vs confirmed vs cancelled) that must survive dynamic colour, which
 * repaints the whole scheme from the user's wallpaper.
 */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val (background, foreground) = when (tone) {
        Tone.Pending -> androidx.compose.ui.graphics.Color(0x33F59E0B) to
            androidx.compose.ui.graphics.Color(0xFFB45309)
        Tone.Positive -> androidx.compose.ui.graphics.Color(0x3310B981) to
            androidx.compose.ui.graphics.Color(0xFF047857)
        Tone.Negative -> androidx.compose.ui.graphics.Color(0x33EF4444) to
            androidx.compose.ui.graphics.Color(0xFFB91C1C)
        Tone.Info -> androidx.compose.ui.graphics.Color(0x333B82F6) to
            androidx.compose.ui.graphics.Color(0xFF1D4ED8)
        Tone.Neutral -> androidx.compose.ui.graphics.Color(0x3371717A) to
            androidx.compose.ui.graphics.Color(0xFF3F3F46)
    }
    Surface(color = background, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
