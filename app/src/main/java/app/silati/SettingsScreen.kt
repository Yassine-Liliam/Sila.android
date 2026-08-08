package app.silati

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.net.toUri
import app.silati.data.Onboarding
import app.silati.data.Options
import app.silati.data.SessionError
import app.silati.data.SettingsRepository
import app.silati.data.SettingsResponse
import app.silati.ui.ButtonSpinner
import app.silati.ui.SheetError
import app.silati.ui.StatusChip
import app.silati.ui.Tone
import app.silati.ui.rememberSheetActionState
import kotlinx.coroutines.launch

/**
 * Settings (Phase 7): profile, business, AI, delivery, Instagram and the danger zone.
 *
 * Saving sends only the fields this screen owns. The backend merges them into the stored
 * onboarding answers, sanitises, and re-derives the AI brief — so the text the AIs read can
 * never drift from the answers, and this screen never has to compose it.
 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loaded by remember { mutableStateOf<SettingsResponse?>(null) }
    var loadFailed by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    val offlineText = stringResource(R.string.sign_in_offline)
    val failedText = stringResource(R.string.list_failed)

    LaunchedEffect(reloads) {
        runCatching { settings.load() }
            .onSuccess { loaded = it; loadFailed = null }
            .onFailure {
                when (it) {
                    is SessionError.SignedOut -> onSignedOut()
                    is SessionError.Offline -> loadFailed = offlineText
                    is SessionError.Failed -> loadFailed = it.detail ?: failedText
                    else -> loadFailed = failedText
                }
            }
    }

    when {
        loadFailed != null && loaded == null -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Text(loadFailed!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { reloads++ }) { Text(stringResource(R.string.retry)) }
            }
        }

        loaded == null -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        else -> SettingsBody(
            data = loaded!!,
            settings = settings,
            onSaved = { loaded = it },
            onSignedOut = onSignedOut,
            onRefresh = { reloads++ },
            modifier = modifier,
        )
    }
}

@Composable
private fun SettingsBody(
    data: SettingsResponse,
    settings: SettingsRepository,
    onSaved: (SettingsResponse) -> Unit,
    onSignedOut: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val a = data.business.onboarding ?: Onboarding()

    var displayName by remember { mutableStateOf(data.user.name.orEmpty()) }
    var businessName by remember { mutableStateOf(a.businessName.orEmpty()) }
    var sells by remember { mutableStateOf(a.sells.orEmpty()) }
    var story by remember { mutableStateOf(a.story.orEmpty()) }
    var aiLanguage by remember { mutableStateOf(a.aiLanguage ?: a.language.orEmpty()) }
    var tone by remember { mutableStateOf(a.tone.orEmpty()) }
    var rules by remember { mutableStateOf(a.rules.orEmpty()) }
    var delivers by remember { mutableStateOf(a.delivers ?: false) }
    var deliveryAreas by remember { mutableStateOf(a.deliveryAreas.orEmpty().joinToString(", ")) }
    var pickupAddress by remember { mutableStateOf(a.pickupAddress.orEmpty()) }
    var payment by remember { mutableStateOf(a.payment.orEmpty()) }
    var phone by remember { mutableStateOf(a.phone.orEmpty()) }
    var confirmInfo by remember { mutableStateOf(a.confirmInfo.orEmpty()) }
    var returnsAccepted by remember { mutableStateOf(a.returnPolicy == "days") }
    var returnDays by remember { mutableStateOf(a.returnDays?.toString() ?: "7") }
    var otherPolicies by remember { mutableStateOf(a.otherPolicies.orEmpty()) }

    val action = rememberSheetActionState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val offlineText = stringResource(R.string.sign_in_offline)
    val failedText = stringResource(R.string.action_failed)
    var confirmDelete by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section(stringResource(R.string.settings_profile)) {
            SettingsField(displayName, { displayName = it }, stringResource(R.string.field_name))
            Text(
                text = data.user.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(stringResource(R.string.settings_business)) {
            SettingsField(
                businessName,
                { businessName = it },
                stringResource(R.string.field_business_name),
            )
            SettingsField(sells, { sells = it }, stringResource(R.string.field_sells))
            SettingsField(
                value = story,
                onValueChange = { story = it },
                label = stringResource(R.string.field_story),
                singleLine = false,
            )
            SettingsField(
                value = phone,
                onValueChange = { phone = it },
                label = stringResource(R.string.field_phone),
                keyboardType = KeyboardType.Phone,
            )
        }

        Section(stringResource(R.string.settings_ai)) {
            ChoiceRow(
                label = stringResource(R.string.field_ai_language),
                options = Options.LANGUAGES,
                selected = aiLanguage,
                onSelect = { aiLanguage = it },
            )
            ChoiceRow(
                label = stringResource(R.string.field_tone),
                options = Options.TONES,
                selected = tone,
                onSelect = { tone = it },
            )
            MultiChoice(
                label = stringResource(R.string.field_rules),
                options = Options.RULES,
                selected = rules,
                onToggle = { rules = rules.toggle(it) },
            )
            Text(
                text = stringResource(R.string.settings_language_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(stringResource(R.string.settings_delivery)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.field_delivers),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = delivers, onCheckedChange = { delivers = it })
            }
            if (delivers) {
                SettingsField(
                    value = deliveryAreas,
                    onValueChange = { deliveryAreas = it },
                    label = stringResource(R.string.field_delivery_areas),
                    supporting = stringResource(R.string.field_comma_separated),
                )
            } else {
                SettingsField(
                    value = pickupAddress,
                    onValueChange = { pickupAddress = it },
                    label = stringResource(R.string.field_pickup_address),
                )
            }
            MultiChoice(
                label = stringResource(R.string.field_payment),
                options = Options.PAYMENTS,
                selected = payment,
                onToggle = { payment = payment.toggle(it) },
            )
            MultiChoice(
                label = stringResource(R.string.field_confirm_info),
                options = Options.CONFIRM_INFO,
                selected = confirmInfo,
                onToggle = { confirmInfo = confirmInfo.toggle(it) },
            )
        }

        Section(stringResource(R.string.settings_policies)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.field_returns),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = returnsAccepted, onCheckedChange = { returnsAccepted = it })
            }
            if (returnsAccepted) {
                SettingsField(
                    value = returnDays,
                    onValueChange = { returnDays = it },
                    label = stringResource(R.string.field_return_days),
                    keyboardType = KeyboardType.Number,
                )
            }
            SettingsField(
                value = otherPolicies,
                onValueChange = { otherPolicies = it },
                label = stringResource(R.string.field_other_policies),
                singleLine = false,
            )
        }

        SheetError(action.error)
        Button(
            onClick = {
                action.busy = true
                action.error = null
                val answers = Onboarding(
                    businessName = businessName.trim(),
                    sells = sells.trim(),
                    story = story.trim(),
                    aiLanguage = aiLanguage.trim(),
                    tone = tone.trim(),
                    rules = rules,
                    delivers = delivers,
                    deliveryAreas = deliveryAreas.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    pickupAddress = pickupAddress.trim(),
                    payment = payment,
                    phone = phone.trim(),
                    confirmInfo = confirmInfo,
                    returnPolicy = if (returnsAccepted) "days" else "none",
                    returnDays = returnDays.trim().toIntOrNull(),
                    otherPolicies = otherPolicies.trim(),
                )
                scope.launch {
                    runCatching { settings.save(name = displayName.trim(), answers = answers) }
                        .onSuccess(onSaved)
                        .onFailure { failure ->
                            when (failure) {
                                is SessionError.SignedOut -> onSignedOut()
                                is SessionError.Offline -> action.error = offlineText
                                is SessionError.Failed -> action.error = failure.detail ?: failedText
                                else -> action.error = failedText
                            }
                        }
                    action.busy = false
                }
            },
            enabled = !action.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (action.busy) ButtonSpinner() else Text(stringResource(R.string.action_save))
        }

        // ── Instagram ───────────────────────────────────────────────────────
        Section(stringResource(R.string.settings_instagram)) {
            if (data.instagram.connected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = data.instagram.username?.let { "@$it" }
                            ?: stringResource(R.string.instagram_connected),
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(stringResource(R.string.instagram_connected), Tone.Positive)
                }
            } else {
                Text(
                    text = stringResource(R.string.instagram_not_connected),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                // Meta requires an HTTPS redirect, so the authorize screen is Instagram's own
                // web page. The handoff means that's the ONLY screen the owner sees.
                text = stringResource(R.string.instagram_connect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        connecting = true
                        action.error = null
                        scope.launch {
                            // Fetched now, not earlier: the code lives ~60s and burns on use.
                            runCatching { settings.instagramConnectUrl() }
                                .onSuccess { openCustomTab(context, it) }
                                .onFailure { failure ->
                                    when (failure) {
                                        is SessionError.SignedOut -> onSignedOut()
                                        is SessionError.Offline -> action.error = offlineText
                                        is SessionError.Failed ->
                                            action.error = failure.detail ?: failedText
                                        else -> action.error = failedText
                                    }
                                }
                            connecting = false
                        }
                    },
                    enabled = !connecting,
                    modifier = Modifier.weight(1f),
                ) {
                    if (connecting) {
                        ButtonSpinner()
                    } else {
                        Text(
                            stringResource(
                                if (data.instagram.connected) R.string.instagram_reconnect
                                else R.string.instagram_connect
                            )
                        )
                    }
                }
                // No deep link back (that would need assetlinks.json + app-link
                // verification), so returning is a back press and the status is refreshed
                // on demand rather than guessed at.
                OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_refresh))
                }
            }
        }

        // ── Danger zone ─────────────────────────────────────────────────────
        Section(stringResource(R.string.settings_danger)) {
            Text(
                text = stringResource(R.string.delete_account_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.delete_account)) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        DeleteAccountDialog(
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    runCatching { settings.deleteAccount() }
                        // The session is gone either way — the User cascade took it.
                        .onSuccess { onSignedOut() }
                        .onFailure {
                            if (it is SessionError.SignedOut) onSignedOut()
                            else action.error = failedText
                        }
                }
            },
        )
    }
}

/** Type-to-confirm, because this cascades every record the business has. */
@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_account)) },
        text = {
            Column {
                Text(stringResource(R.string.delete_account_warning))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.delete_account_type)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim() == "DELETE") {
                Text(
                    text = stringResource(R.string.delete_account),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            content()
        }
    }
}

@Composable
private fun SettingsField(
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
            .padding(bottom = 8.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        supportingText = supporting?.let { { Text(it) } },
    )
}

/**
 * The owner-facing label for an option value.
 *
 * The values themselves stay canonical English — they're stored and fed to the AI — so only
 * the label is translated. An unmapped value shows itself, which is what makes adding an
 * option to the web wizard degrade to "untranslated" rather than "blank".
 */
@Composable
private fun optionLabel(value: String): String = when (value) {
    "English" -> stringResource(R.string.opt_lang_english)
    "French" -> stringResource(R.string.opt_lang_french)
    "Arabic" -> stringResource(R.string.opt_lang_arabic)
    "professional" -> stringResource(R.string.opt_tone_professional)
    "casual" -> stringResource(R.string.opt_tone_casual)
    "friendly" -> stringResource(R.string.opt_tone_friendly)
    "Always stay polite and patient" -> stringResource(R.string.opt_rule_polite)
    "Confirm order details before closing" -> stringResource(R.string.opt_rule_confirm)
    "Never promise discounts or prices not listed" -> stringResource(R.string.opt_rule_discounts)
    "Escalate complaints or refunds to a human" -> stringResource(R.string.opt_rule_escalate)
    "Only answer questions about our products" -> stringResource(R.string.opt_rule_products_only)
    "Never share personal opinions" -> stringResource(R.string.opt_rule_no_opinions)
    "Cash on delivery" -> stringResource(R.string.opt_pay_cod)
    "Bank transfer" -> stringResource(R.string.opt_pay_transfer)
    "Cash" -> stringResource(R.string.opt_pay_cash)
    "Full name" -> stringResource(R.string.opt_info_name)
    "City" -> stringResource(R.string.opt_info_city)
    "Address" -> stringResource(R.string.opt_info_address)
    "Phone number" -> stringResource(R.string.opt_info_phone)
    "Email address" -> stringResource(R.string.opt_info_email)
    else -> value
}

/** Single-select chips. */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach {
            FilterChip(
                selected = selected.equals(it, ignoreCase = true),
                onClick = { onSelect(it) },
                label = { Text(optionLabel(it)) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** Multi-select chips. */
@Composable
private fun MultiChoice(
    label: String,
    options: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach {
            FilterChip(
                selected = it in selected,
                onClick = { onToggle(it) },
                label = { Text(optionLabel(it)) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

private fun List<String>.toggle(value: String) =
    if (value in this) this - value else this + value

/**
 * Opens a Chrome Custom Tab — an in-app browser overlay rather than a jump to Chrome, so the
 * owner stays in the app and one back press returns them.
 *
 * Falls back to a plain browser intent on devices with no Custom Tabs provider; the flow
 * still works, it just looks like leaving the app.
 */
private fun openCustomTab(context: Context, url: String) {
    val uri = url.toUri()
    runCatching {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, uri)
    }.onFailure {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
