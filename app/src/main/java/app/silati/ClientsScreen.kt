package app.silati

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.silati.data.Client
import app.silati.data.ClientRepository
import app.silati.ui.PagedList
import app.silati.ui.SheetActions
import app.silati.ui.StatusChip
import app.silati.ui.Tone
import app.silati.ui.date

/** The customer list. Read-only in Phase 5. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(
    clients: ClientRepository,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Client?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Editing<Client>?>(null) }

    editing?.let { target ->
        ClientForm(
            initial = target.value,
            clients = clients,
            onSaved = {
                editing = null
                reloadKey++
            },
            onCancel = { editing = null },
            onSignedOut = onSignedOut,
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.clients_search),
            )
            PagedList(
                filter = query,
                load = { cursor, search -> clients.page(cursor, search) },
                itemKey = { it.id },
                emptyText = stringResource(R.string.clients_empty),
                emptyTextWhenFiltered = stringResource(R.string.clients_no_match),
                emptyIcon = Icons.Default.Person,
                emptyActionLabel = stringResource(R.string.client_new),
                onEmptyAction = { editing = Editing(null) },
                onSignedOut = onSignedOut,
                reloadKey = reloadKey,
            ) { client ->
                ClientRow(client) { selected = client }
            }
        }
        AddButton(
            description = stringResource(R.string.client_new),
            onClick = { editing = Editing(null) },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }

    selected?.let { client ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SheetBody {
                Text(text = client.name, style = MaterialTheme.typography.headlineSmall)
                if (client.conversationId != null) {
                    Spacer(Modifier.height(8.dp))
                    StatusChip(stringResource(R.string.client_from_instagram), Tone.Info)
                }
                DetailLine(stringResource(R.string.field_phone), client.phone)
                DetailLine(stringResource(R.string.field_email), client.email)
                DetailLine(stringResource(R.string.field_address), client.address)
                DetailLine(stringResource(R.string.field_city), client.city)
                DetailLine(stringResource(R.string.field_notes), client.notes)
                DetailLine(stringResource(R.string.field_client_since), date(client.createdAt))
                SheetActions {
                    Button(
                        onClick = {
                            selected = null
                            editing = Editing(client)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_edit)) }
                }
            }
        }
    }
}

/**
 * The client's initial, so a list of names has something to anchor the eye at the leading
 * edge — the same job the thumbnail does on Products.
 *
 * Decorative: the name is right beside it, so a screen reader announcing the letter as well
 * would only repeat what it is about to read.
 */
@Composable
private fun Avatar(name: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // First letter of the name, uppercased in the *display* locale: Arabic and French
            // names both go through here, and the value itself is never touched.
            text = name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun ClientRow(client: Client, onClick: () -> Unit) {
    EntityRow(onClick) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(client.name)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = client.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                client.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (client.conversationId != null) {
                StatusChip(stringResource(R.string.client_instagram_short), Tone.Info)
            }
        }
    }
}
