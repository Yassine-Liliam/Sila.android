package app.silati

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.silati.data.Delivery
import app.silati.data.DeliveryRepository
import app.silati.data.SessionError
import app.silati.ui.PagedList
import app.silati.ui.SheetError
import app.silati.ui.StatusChip
import app.silati.ui.StatusDot
import app.silati.ui.Tone
import app.silati.ui.dateTime
import app.silati.ui.relativeTime
import app.silati.ui.rememberSheetActionState
import kotlinx.coroutines.launch

/**
 * Deliveries. Read-only in Phase 5 — status changes are Phase 6.
 *
 * A delivery only ever exists because a purchase was confirmed while the business delivers
 * and the client had an address, so there is deliberately no way to create one here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveriesScreen(
    deliveries: DeliveryRepository,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Delivery?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        StatusFilter(
            selected = status,
            options = DELIVERY_STATUSES,
            onSelect = { status = it },
            label = { deliveryStatusLabel(it) },
        )
        PagedList(
            filter = status,
            load = { cursor, filter -> deliveries.page(cursor, filter) },
            itemKey = { it.id },
            emptyText = stringResource(R.string.deliveries_empty),
            emptyTextWhenFiltered = stringResource(R.string.deliveries_no_match),
            // No action: confirming a purchase is what creates a delivery.
            emptyIcon = Icons.Default.Place,
            onSignedOut = onSignedOut,
            reloadKey = reloadKey,
        ) { delivery ->
            DeliveryRow(delivery) { selected = delivery }
        }
    }

    selected?.let { delivery ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SheetBody {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = delivery.client?.name ?: stringResource(R.string.unknown_client),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(
                        text = stringResource(deliveryStatusLabel(delivery.status)),
                        tone = deliveryTone(delivery.status),
                    )
                }
                delivery.total?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$it ${delivery.currency}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                DetailLine(stringResource(R.string.field_address), delivery.where)
                DetailLine(stringResource(R.string.field_phone), delivery.phone)
                DetailLine(stringResource(R.string.field_created), dateTime(delivery.createdAt))
                DetailLine(stringResource(R.string.field_scheduled), dateTime(delivery.scheduledAt))
                DetailLine(
                    stringResource(R.string.field_delivered_at),
                    dateTime(delivery.deliveredAt),
                )
                DetailLine(stringResource(R.string.field_notes), delivery.notes)
                DeliveryActions(
                    delivery = delivery,
                    deliveries = deliveries,
                    onSignedOut = onSignedOut,
                    onDone = {
                        selected = null
                        reloadKey++
                    },
                )
            }
        }
    }
}

/**
 * Move a delivery along. Every status except its current one is offered — including going
 * backwards, because a courier handing a parcel back is a real thing and the owner is the
 * one who knows.
 */
@Composable
private fun DeliveryActions(
    delivery: Delivery,
    deliveries: DeliveryRepository,
    onSignedOut: () -> Unit,
    onDone: () -> Unit,
) {
    val action = rememberSheetActionState()
    val scope = rememberCoroutineScope()
    val failedText = stringResource(R.string.action_failed)
    val offlineText = stringResource(R.string.sign_in_offline)

    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.delivery_set_status),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DELIVERY_STATUSES.filter { it != delivery.status }.forEach { next ->
            AssistChip(
                onClick = {
                    action.busy = true
                    action.error = null
                    scope.launch {
                        runCatching { deliveries.setStatus(delivery.id, next) }
                            .onSuccess { onDone() }
                            .onFailure { failure ->
                                when (failure) {
                                    is SessionError.SignedOut -> onSignedOut()
                                    is SessionError.Offline -> action.error = offlineText
                                    is SessionError.Failed ->
                                        action.error = failure.detail ?: failedText
                                    else -> action.error = failedText
                                }
                            }
                        action.busy = false
                    }
                },
                enabled = !action.busy,
                label = { Text(stringResource(deliveryStatusLabel(next))) },
            )
        }
    }
    SheetError(action.error)
}

@Composable
private fun DeliveryRow(delivery: Delivery, onClick: () -> Unit) {
    EntityRow(onClick) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Repeats the chip's meaning on the leading edge, where a column of dots reads as
            // "what still needs me" without having to read any of the words.
            StatusDot(deliveryTone(delivery.status))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = delivery.client?.name ?: stringResource(R.string.unknown_client),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = delivery.where,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusChip(
                    text = stringResource(deliveryStatusLabel(delivery.status)),
                    tone = deliveryTone(delivery.status),
                )
                // Delivered date once it's done, otherwise when it was created — whichever
                // answers "is this still waiting on me?".
                relativeTime(delivery.deliveredAt ?: delivery.createdAt)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// Values must match the Prisma enums — the backend rejects anything else with a 400.
val DELIVERY_STATUSES = listOf("pending", "in_transit", "delivered", "failed", "cancelled")

fun deliveryStatusLabel(status: String) = when (status) {
    "pending" -> R.string.delivery_pending
    "in_transit" -> R.string.delivery_in_transit
    "delivered" -> R.string.delivery_delivered
    "failed" -> R.string.delivery_failed
    "cancelled" -> R.string.delivery_cancelled
    else -> R.string.status_unknown
}

fun deliveryTone(status: String) = when (status) {
    "pending" -> Tone.Pending
    "in_transit" -> Tone.Info
    "delivered" -> Tone.Positive
    "failed" -> Tone.Negative
    "cancelled" -> Tone.Neutral
    else -> Tone.Neutral
}
