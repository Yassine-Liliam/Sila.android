package app.silati.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : Any> PagedList(
    filter: String,
    load: suspend (cursor: String?, filter: String) -> Page<T>,
    itemKey: (T) -> String,
    emptyText: String,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    emptyTextWhenFiltered: String? = null,
    emptyIcon: ImageVector? = null,
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null,
    reloadKey: Int = 0,
    row: @Composable (T) -> Unit,
) {
    var items by remember { mutableStateOf<List<T>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var reachedEnd by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    // Separate from `loading`, which is also true while a next page is being fetched — the
    // pull indicator must only reflect a pull.
    var refreshing by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val snackbars = remember { SnackbarHostState() }
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
        // Where the owner was looking, so a write far down the list doesn't fling them back
        // to the top. ponytail: only the first page is re-fetched, so this restores the
        // common case (a list that fits in one page) and clamps to the end otherwise — the
        // real fix is re-fetching as many pages as were loaded.
        val anchor = listState.firstVisibleItemIndex
        val anchorOffset = listState.firstVisibleItemScrollOffset
        loading = true
        error = null
        runCatching { load(null, filter) }
            .onSuccess {
                items = it.items
                cursor = it.nextCursor
                reachedEnd = it.nextCursor == null
                if (anchor in 1 until it.items.size) {
                    listState.scrollToItem(anchor, anchorOffset)
                }
            }
            .onFailure(::report)
        loading = false
        refreshing = false
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
            items.isEmpty() && loading -> SkeletonRows()

            items.isEmpty() && error != null -> ListError(error!!) { reloads++ }

            items.isEmpty() -> {
                val filtered = filter.isNotBlank() && emptyTextWhenFiltered != null
                ListEmpty(
                    text = if (filtered) emptyTextWhenFiltered else emptyText,
                    icon = emptyIcon,
                    // A search that found nothing is not an empty list: offering "add one"
                    // there would create a row that has nothing to do with what was typed.
                    actionLabel = emptyActionLabel.takeUnless { filtered },
                    onAction = onEmptyAction.takeUnless { filtered },
                )
            }

            // Pull-to-refresh is the gesture people try first on a list they think is stale.
            // It reuses the same reload path as the retry button and a write action, so there
            // is one way a list re-reads itself.
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    reloads++
                },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Keyed + animateItem: a write re-fetches the whole first page, so rows
                    // that moved (or a confirmed order leaving a filtered list) slide instead
                    // of snapping to their new place.
                    items(items, key = itemKey) { Box(Modifier.animateItem()) { row(it) } }
                    if (!reachedEnd) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
        }

        // A failure with rows already on screen shouldn't blank them out — it gets a snackbar
        // with a Retry instead, the Material way of saying "this didn't work, here's the fix".
        // The host lives here rather than in the Scaffold so no screen has to plumb one down.
        SnackbarHost(
            hostState = snackbars,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    val retryLabel = stringResource(R.string.retry)
    LaunchedEffect(error, items.isEmpty()) {
        val message = error
        if (message != null && items.isNotEmpty()) {
            val result = snackbars.showSnackbar(
                message = message,
                actionLabel = retryLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) reloads++
        }
    }
}

/**
 * Placeholder rows while the first page loads.
 *
 * A centred spinner told the owner nothing about what was coming and let the layout jump when
 * it arrived; blocks the size of rows keep the page still. Deliberately not shimmering — the
 * animation is the part that costs code, and the stillness is what fixed the problem.
 */
@Composable
private fun SkeletonRows() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(6) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/**
 * An empty list: an icon so the screen isn't a lone sentence, the explanation, and the action
 * that fills it when there is one. Products used to read "ask the assistant to add one" while
 * its own **+** sat in the corner — the button says it better than the sentence did.
 */
@Composable
private fun ListEmpty(
    text: String,
    icon: ImageVector?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction) { Text(actionLabel) }
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
 * The colour a [Tone] paints with, as a translucent fill and the text on top of it.
 *
 * Literal colours rather than theme roles, because order status carries meaning — pending vs
 * confirmed vs cancelled — that must survive dynamic colour repainting the scheme from the
 * owner's wallpaper. But the text has to flip with the theme: a dark amber on a translucent
 * fill is legible over white and close to invisible over black, which is what the single
 * hardcoded pair used to do in dark mode. Light theme takes the 700-level, dark takes the
 * 300/400-level, the same split the web's badges use.
 */
@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    return when (tone) {
        Tone.Pending -> Color(0x33F59E0B) to if (dark) Color(0xFFFCD34D) else Color(0xFFB45309)
        Tone.Positive -> Color(0x3310B981) to if (dark) Color(0xFF6EE7B7) else Color(0xFF047857)
        Tone.Negative -> Color(0x33EF4444) to if (dark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
        Tone.Info -> Color(0x333B82F6) to if (dark) Color(0xFF93C5FD) else Color(0xFF1D4ED8)
        Tone.Neutral -> Color(0x3371717A) to if (dark) Color(0xFFD4D4D8) else Color(0xFF3F3F46)
    }
}

/** A small status pill. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val (background, foreground) = toneColors(tone)
    Surface(color = background, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * The same meaning as a [StatusChip] but as a dot, for a row's leading edge where a second
 * pill would just be noise. Decorative on purpose: the status is always also written out in
 * words nearby, so a screen reader hearing this twice would be worse than not hearing it.
 */
@Composable
fun StatusDot(tone: Tone, modifier: Modifier = Modifier) {
    val (_, foreground) = toneColors(tone)
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(foreground),
    )
}
