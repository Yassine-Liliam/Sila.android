package app.silati

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.silati.data.Delivery
import app.silati.data.DeliveryRepository
import app.silati.ui.PagedList
import app.silati.ui.StatusChip
import app.silati.ui.Tone

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
            onSignedOut = onSignedOut,
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
                DetailLine(stringResource(R.string.field_scheduled), delivery.scheduledAt)
                DetailLine(stringResource(R.string.field_delivered_at), delivery.deliveredAt)
                DetailLine(stringResource(R.string.field_notes), delivery.notes)
            }
        }
    }
}

@Composable
private fun DeliveryRow(delivery: Delivery, onClick: () -> Unit) {
    EntityRow(onClick) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            StatusChip(
                text = stringResource(deliveryStatusLabel(delivery.status)),
                tone = deliveryTone(delivery.status),
            )
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
