package app.silati

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import app.silati.data.Purchase
import app.silati.data.PurchaseRepository
import app.silati.ui.PagedList
import app.silati.ui.StatusChip
import app.silati.ui.Tone

/** Orders. Confirm/cancel are Phase 6 — for now the assistant does them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchasesScreen(
    purchases: PurchaseRepository,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Purchase?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        StatusFilter(
            selected = status,
            options = PURCHASE_STATUSES,
            onSelect = { status = it },
            label = { purchaseStatusLabel(it) },
        )
        PagedList(
            filter = status,
            load = { cursor, filter -> purchases.page(cursor, filter) },
            itemKey = { it.id },
            emptyText = stringResource(R.string.purchases_empty),
            emptyTextWhenFiltered = stringResource(R.string.purchases_no_match),
            onSignedOut = onSignedOut,
        ) { purchase ->
            PurchaseRow(purchase) { selected = purchase }
        }
    }

    selected?.let { purchase ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SheetBody {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = purchase.client?.name ?: stringResource(R.string.unknown_client),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(
                        text = stringResource(purchaseStatusLabel(purchase.status)),
                        tone = purchaseTone(purchase.status),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = purchase.displayTotal,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                purchase.items.forEach { item ->
                    Row(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = "${item.quantity} × ${item.productName}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${item.unitPrice} ${purchase.currency}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                DetailLine(stringResource(R.string.field_source), sourceLabel(purchase.source))
                DetailLine(stringResource(R.string.field_notes), purchase.notes)
                purchase.delivery?.let {
                    DetailLine(
                        stringResource(R.string.field_delivery),
                        stringResource(deliveryStatusLabel(it.status)),
                    )
                }
            }
        }
    }
}

@Composable
private fun PurchaseRow(purchase: Purchase, onClick: () -> Unit) {
    EntityRow(onClick) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = purchase.client?.name ?: stringResource(R.string.unknown_client),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = purchase.items.joinToString(", ") { "${it.quantity}× ${it.productName}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = purchase.displayTotal, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                StatusChip(
                    text = stringResource(purchaseStatusLabel(purchase.status)),
                    tone = purchaseTone(purchase.status),
                )
            }
        }
    }
}

/** A scrollable row of status filters, with "All" first. Shared by purchases + deliveries. */
@Composable
fun StatusFilter(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    label: (String) -> Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected.isEmpty(),
            onClick = { onSelect("") },
            label = { Text(stringResource(R.string.filter_all)) },
        )
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(stringResource(label(option))) },
            )
        }
    }
}

// Values must match the Prisma enums — the backend rejects anything else with a 400.
val PURCHASE_STATUSES = listOf("pending", "confirmed", "cancelled")

fun purchaseStatusLabel(status: String) = when (status) {
    "pending" -> R.string.purchase_pending
    "confirmed" -> R.string.purchase_confirmed
    "cancelled" -> R.string.purchase_cancelled
    else -> R.string.status_unknown
}

fun purchaseTone(status: String) = when (status) {
    "pending" -> Tone.Pending
    "confirmed" -> Tone.Positive
    "cancelled" -> Tone.Neutral
    else -> Tone.Neutral
}

@Composable
private fun sourceLabel(source: String) = stringResource(
    when (source) {
        "manual" -> R.string.source_manual
        "ai_instagram" -> R.string.source_instagram
        "ai_chat" -> R.string.source_assistant
        else -> R.string.status_unknown
    }
)
