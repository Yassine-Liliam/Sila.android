package app.silati

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.silati.data.Client
import app.silati.data.ClientInput
import app.silati.data.ClientRepository
import app.silati.data.Product
import app.silati.data.ProductInput
import app.silati.data.ProductRepository
import app.silati.data.SessionError
import app.silati.data.absoluteUrl
import app.silati.data.encodeImage
import coil3.compose.AsyncImage
import app.silati.ui.ButtonSpinner
import app.silati.ui.SheetError
import app.silati.ui.rememberSheetActionState
import kotlinx.coroutines.launch

/**
 * Create / edit forms (Phase 6b).
 *
 * These are full-screen states owned by their list screen, not navigation destinations. The
 * repo convention says nav stays a state value "until a screen actually needs a back stack,
 * arguments or deep links" — a form reached from exactly one place needs none of those. The
 * argument is the entity itself, passed directly; back is one [BackHandler]. Adding
 * navigation-compose would mean restructuring working navigation to gain nothing.
 * ponytail: revisit when something needs a deep link (a push notification opening an order
 * is the likely trigger) — that's a real back stack and the library earns its place then.
 */

/** Shared chrome: scrolling body, Save / Cancel, busy and error handling. */
@Composable
private fun FormScaffold(
    title: String,
    saving: Boolean,
    error: String?,
    canSave: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    fields: @Composable () -> Unit,
) {
    BackHandler(enabled = !saving) { onCancel() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        fields()
        SheetError(error)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = onSave,
                enabled = !saving && canSave,
                modifier = Modifier.weight(1f),
            ) {
                if (saving) ButtonSpinner() else Text(stringResource(R.string.action_save))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        supportingText = supporting?.let { { Text(it) } },
    )
}

/** Maps a repository failure onto something to show, or bounces a dead session upward. */
private inline fun handleFailure(
    failure: Throwable,
    onSignedOut: () -> Unit,
    offline: String,
    generic: String,
    setError: (String) -> Unit,
) = when (failure) {
    is SessionError.SignedOut -> onSignedOut()
    is SessionError.Offline -> setError(offline)
    is SessionError.Failed -> setError(failure.detail ?: generic)
    else -> setError(generic)
}

// ── Product ─────────────────────────────────────────────────────────────────

/** @param initial null to create, otherwise the product being edited. */
@Composable
fun ProductForm(
    initial: Product?,
    products: ProductRepository,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var price by remember { mutableStateOf(initial?.price.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    // Empty means "not tracked" — the same thing it means on the wire.
    var stock by remember { mutableStateOf(initial?.stock?.toString().orEmpty()) }
    var currency by remember { mutableStateOf(initial?.currency ?: "MAD") }
    var active by remember { mutableStateOf(initial?.active ?: true) }
    // The picked photo is held as a Uri, not as bytes: it previews straight from the Uri and
    // the encode (decode, downscale, base64) happens off the main thread at save time.
    var picked by remember { mutableStateOf<Uri?>(null) }
    var removeImage by remember { mutableStateOf(false) }

    val action = rememberSheetActionState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val offlineText = stringResource(R.string.sign_in_offline)
    val failedText = stringResource(R.string.action_failed)
    val imageFailedText = stringResource(R.string.image_failed)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            picked = uri
            removeImage = false
        }
    }

    // Mirrors the server: name required, price a number >= 0.
    val priceValid = price.trim().toDoubleOrNull()?.let { it >= 0 } == true
    val canSave = name.isNotBlank() && priceValid

    FormScaffold(
        title = stringResource(
            if (initial == null) R.string.product_new else R.string.product_edit
        ),
        saving = action.busy,
        error = action.error,
        canSave = canSave,
        onCancel = onCancel,
        onSave = {
            action.busy = true
            action.error = null
            scope.launch {
                val chosen = picked
                val image = if (chosen != null) encodeImage(context, chosen) else null
                if (chosen != null && image == null) {
                    // Unreadable or undecodable: say so rather than silently saving the rest.
                    action.error = imageFailedText
                    action.busy = false
                    return@launch
                }
                val input = ProductInput(
                    name = name.trim(),
                    price = price.trim(),
                    description = description.trim(),
                    stock = stock.trim(),
                    currency = currency.trim().ifBlank { "MAD" },
                    active = active,
                    image = image,
                    removeImage = if (removeImage && image == null) true else null,
                )
                runCatching {
                    if (initial == null) products.create(input)
                    else products.update(initial.id, input)
                }
                    .onSuccess { onSaved() }
                    .onFailure {
                        handleFailure(it, onSignedOut, offlineText, failedText) { m ->
                            action.error = m
                        }
                    }
                action.busy = false
            }
        },
        modifier = modifier,
    ) {
        ProductImageField(
            picked = picked,
            existing = initial?.imageUrl?.takeUnless { removeImage },
            onPick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onRemove = {
                picked = null
                removeImage = true
            },
        )
        FormField(name, { name = it }, stringResource(R.string.field_name))
        FormField(
            value = price,
            onValueChange = { price = it },
            label = stringResource(R.string.field_price),
            keyboardType = KeyboardType.Decimal,
            supporting = if (price.isNotBlank() && !priceValid) {
                stringResource(R.string.field_price_invalid)
            } else null,
        )
        FormField(
            value = currency,
            onValueChange = { currency = it },
            label = stringResource(R.string.field_currency),
        )
        FormField(
            value = stock,
            onValueChange = { stock = it },
            label = stringResource(R.string.field_stock),
            keyboardType = KeyboardType.Number,
            supporting = stringResource(R.string.field_stock_hint),
        )
        FormField(
            value = description,
            onValueChange = { description = it },
            label = stringResource(R.string.field_description),
            singleLine = false,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.field_active),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.field_active_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = active, onCheckedChange = { active = it })
        }
    }
}

/**
 * The photo row: preview, pick, and remove.
 *
 * @param picked a just-chosen image, previewed straight from its Uri — Coil reads `content://`
 *   as happily as `https://`, so nothing has to be decoded to show it.
 * @param existing the product's stored image, already cleared by the caller when removal is
 *   pending, so this composable never has to know which of the two states wins.
 */
@Composable
private fun ProductImageField(
    picked: Uri?,
    existing: String?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val model: Any? = picked ?: existing?.let { absoluteUrl(it) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model == null) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.image_current),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(shape),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            OutlinedButton(onClick = onPick) {
                Text(
                    stringResource(
                        if (model == null) R.string.image_add else R.string.image_change
                    )
                )
            }
            if (model != null) {
                TextButton(onClick = onRemove) {
                    Text(
                        text = stringResource(R.string.image_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ── Client ──────────────────────────────────────────────────────────────────

@Composable
fun ClientForm(
    initial: Client?,
    clients: ClientRepository,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var city by remember { mutableStateOf(initial?.city.orEmpty()) }
    var email by remember { mutableStateOf(initial?.email.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }

    val action = rememberSheetActionState()
    val scope = rememberCoroutineScope()
    val offlineText = stringResource(R.string.sign_in_offline)
    val failedText = stringResource(R.string.action_failed)

    FormScaffold(
        title = stringResource(
            if (initial == null) R.string.client_new else R.string.client_edit
        ),
        saving = action.busy,
        error = action.error,
        canSave = name.isNotBlank(),
        onCancel = onCancel,
        onSave = {
            action.busy = true
            action.error = null
            val input = ClientInput(
                name = name.trim(),
                phone = phone.trim(),
                address = address.trim(),
                city = city.trim(),
                email = email.trim(),
                notes = notes.trim(),
            )
            scope.launch {
                runCatching {
                    if (initial == null) clients.create(input)
                    else clients.update(initial.id, input)
                }
                    .onSuccess { onSaved() }
                    .onFailure {
                        handleFailure(it, onSignedOut, offlineText, failedText) { m ->
                            action.error = m
                        }
                    }
                action.busy = false
            }
        },
        modifier = modifier,
    ) {
        FormField(name, { name = it }, stringResource(R.string.field_name))
        FormField(
            value = phone,
            onValueChange = { phone = it },
            label = stringResource(R.string.field_phone),
            keyboardType = KeyboardType.Phone,
        )
        FormField(
            value = email,
            onValueChange = { email = it },
            label = stringResource(R.string.field_email),
            keyboardType = KeyboardType.Email,
        )
        FormField(address, { address = it }, stringResource(R.string.field_address))
        FormField(city, { city = it }, stringResource(R.string.field_city))
        FormField(
            value = notes,
            onValueChange = { notes = it },
            label = stringResource(R.string.field_notes),
            singleLine = false,
        )
        Text(
            text = stringResource(R.string.client_form_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}
