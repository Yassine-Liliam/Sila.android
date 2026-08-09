package app.silati.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.silati.R

/**
 * The controls that edit a business's onboarding answers.
 *
 * Shared by Settings (which edits them) and Onboarding (which collects them for the first
 * time) — the two screens ask the same questions, so asking them twice in code would mean
 * every future field lands in two places and eventually only one.
 */

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            content()
        }
    }
}

@Composable
internal fun AnswerField(
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
internal fun optionLabel(value: String): String = when (value) {
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
internal fun ChoiceRow(
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
internal fun MultiChoice(
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

internal fun List<String>.toggle(value: String) =
    if (value in this) this - value else this + value
