package app.silati

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.silati.data.Onboarding
import app.silati.data.Options
import app.silati.data.Session
import app.silati.data.SessionError
import app.silati.data.SessionRepository
import app.silati.ui.AnswerField
import app.silati.ui.ButtonSpinner
import app.silati.ui.ChoiceRow
import app.silati.ui.MultiChoice
import app.silati.ui.Section
import app.silati.ui.SheetError
import app.silati.ui.toggle
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * First-run setup (Phase 3): the five questions that create the owner's first business.
 *
 * Mirrors the web wizard's steps so the two ask the same things in the same order, and reuses
 * the Settings screen's field controls (`ui/AnswerFields.kt`) — this screen collects the
 * answers, Settings edits them afterwards, and neither composes the AI brief: the backend
 * derives it from the answers, which is what stops the brief from drifting.
 *
 * ponytail: steps are an Int and a `when`, not a nav graph. Nothing here needs a back stack,
 * arguments or a deep link — back is one BackHandler — so the library would buy nothing.
 */
@Composable
fun OnboardingScreen(
    sessions: SessionRepository,
    userName: String?,
    onDone: (Session) -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = 5
    // Every answer is saveable: a rotation part-way through the wizard used to empty the form,
    // which is the worst possible moment to lose typing. Strings, booleans and lists of
    // strings all go into the Bundle as they are, so no custom Saver is needed.
    var step by rememberSaveable { mutableIntStateOf(1) }

    // The device language is the honest default for a business that just installed a phone
    // app in it. It stays a question, not an assumption — the owner may sell in another one.
    val deviceLanguage = remember {
        when (Locale.getDefault().language) {
            "fr" -> "French"
            "ar" -> "Arabic"
            else -> "English"
        }
    }

    var name by rememberSaveable { mutableStateOf(userName.orEmpty()) }
    var businessName by rememberSaveable { mutableStateOf("") }
    var sells by rememberSaveable { mutableStateOf("") }
    var story by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf(deviceLanguage) }
    var aiLanguage by rememberSaveable { mutableStateOf(deviceLanguage) }
    var tone by rememberSaveable { mutableStateOf("professional") } // the web wizard's default
    var rules by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // Null until answered: this gates whether confirming an order creates a delivery, so a
    // silent default would break deliveries in a way the owner can't see.
    var delivers by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var deliveryAreas by rememberSaveable { mutableStateOf("") }
    var pickupAddress by rememberSaveable { mutableStateOf("") }
    var payment by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var phone by rememberSaveable { mutableStateOf("") }
    var confirmInfo by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var deliveryInstructions by rememberSaveable { mutableStateOf("") }
    var returnsAccepted by rememberSaveable { mutableStateOf(false) }
    var returnDays by rememberSaveable { mutableStateOf("7") }
    var otherPolicies by rememberSaveable { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val offlineText = stringResource(R.string.sign_in_offline)
    val failedText = stringResource(R.string.action_failed)

    // Only what the backend actually requires, mirrored here so the owner is told before the
    // round trip rather than by a 400.
    val canAdvance = when (step) {
        1 -> businessName.isNotBlank() && sells.isNotBlank() && language.isNotBlank()
        3 -> delivers != null
        else -> true
    }

    BackHandler(enabled = step > 1) { step-- }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .padding(16.dp),
    ) {
        LinearProgressIndicator(
            progress = { step.toFloat() / steps },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_step, step, steps),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                1 -> Section(stringResource(R.string.onboarding_step_business)) {
                    Text(
                        text = stringResource(R.string.onboarding_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    AnswerField(name, { name = it }, stringResource(R.string.field_your_name))
                    AnswerField(
                        businessName,
                        { businessName = it },
                        stringResource(R.string.field_business_name),
                    )
                    AnswerField(sells, { sells = it }, stringResource(R.string.field_sells))
                    AnswerField(
                        value = story,
                        onValueChange = { story = it },
                        label = stringResource(R.string.field_story),
                        singleLine = false,
                    )
                    ChoiceRow(
                        label = stringResource(R.string.field_language),
                        options = Options.LANGUAGES,
                        selected = language,
                        onSelect = {
                            // Follow the language unless the AI's has been set apart from it.
                            if (aiLanguage == language) aiLanguage = it
                            language = it
                        },
                    )
                }

                2 -> Section(stringResource(R.string.onboarding_step_ai)) {
                    ChoiceRow(
                        label = stringResource(R.string.field_ai_language),
                        options = Options.LANGUAGES,
                        selected = aiLanguage,
                        onSelect = { aiLanguage = it },
                    )
                    Text(
                        text = stringResource(R.string.settings_language_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
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
                }

                3 -> Section(stringResource(R.string.onboarding_step_delivery)) {
                    Text(
                        text = stringResource(R.string.field_delivers_question),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        FilterChip(
                            selected = delivers == true,
                            onClick = { delivers = true },
                            label = { Text(stringResource(R.string.action_yes)) },
                        )
                        FilterChip(
                            selected = delivers == false,
                            onClick = { delivers = false },
                            label = { Text(stringResource(R.string.action_no)) },
                        )
                    }
                    when (delivers) {
                        true -> AnswerField(
                            value = deliveryAreas,
                            onValueChange = { deliveryAreas = it },
                            label = stringResource(R.string.field_delivery_areas),
                            supporting = stringResource(R.string.field_comma_separated),
                        )

                        false -> AnswerField(
                            value = pickupAddress,
                            onValueChange = { pickupAddress = it },
                            label = stringResource(R.string.field_pickup_address),
                        )

                        null -> Unit
                    }
                    MultiChoice(
                        label = stringResource(R.string.field_payment),
                        options = Options.PAYMENTS,
                        selected = payment,
                        onToggle = { payment = payment.toggle(it) },
                    )
                    AnswerField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = stringResource(R.string.field_phone),
                        keyboardType = KeyboardType.Phone,
                    )
                }

                4 -> Section(stringResource(R.string.onboarding_step_orders)) {
                    MultiChoice(
                        label = stringResource(R.string.field_confirm_info),
                        options = Options.CONFIRM_INFO,
                        selected = confirmInfo,
                        onToggle = { confirmInfo = confirmInfo.toggle(it) },
                    )
                    AnswerField(
                        value = deliveryInstructions,
                        onValueChange = { deliveryInstructions = it },
                        label = stringResource(R.string.field_delivery_instructions),
                        singleLine = false,
                    )
                }

                else -> Section(stringResource(R.string.onboarding_step_policies)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.field_returns),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Switch(checked = returnsAccepted, onCheckedChange = { returnsAccepted = it })
                    }
                    if (returnsAccepted) {
                        AnswerField(
                            value = returnDays,
                            onValueChange = { returnDays = it },
                            label = stringResource(R.string.field_return_days),
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    AnswerField(
                        value = otherPolicies,
                        onValueChange = { otherPolicies = it },
                        label = stringResource(R.string.field_other_policies),
                        singleLine = false,
                    )
                }
            }
            SheetError(error)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 1) {
                OutlinedButton(
                    onClick = { step-- },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_back)) }
            }
            Button(
                onClick = {
                    if (step < steps) {
                        step++
                        return@Button
                    }
                    busy = true
                    error = null
                    val answers = Onboarding(
                        userName = name.trim(),
                        businessName = businessName.trim(),
                        sells = sells.trim(),
                        story = story.trim(),
                        language = language,
                        aiLanguage = aiLanguage,
                        tone = tone,
                        rules = rules,
                        delivers = delivers == true,
                        deliveryAreas = deliveryAreas.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() },
                        pickupAddress = pickupAddress.trim(),
                        payment = payment,
                        phone = phone.trim(),
                        confirmInfo = confirmInfo,
                        deliveryInstructions = deliveryInstructions.trim(),
                        returnPolicy = if (returnsAccepted) "days" else "none",
                        returnDays = returnDays.trim().toIntOrNull(),
                        otherPolicies = otherPolicies.trim(),
                    )
                    scope.launch {
                        runCatching { sessions.onboard(answers) }
                            .onSuccess(onDone)
                            .onFailure { failure ->
                                when (failure) {
                                    is SessionError.SignedOut -> onSignedOut()
                                    is SessionError.Offline -> error = offlineText
                                    is SessionError.Failed -> error = failure.detail ?: failedText
                                    else -> error = failedText
                                }
                            }
                        busy = false
                    }
                },
                enabled = canAdvance && !busy,
                modifier = Modifier.weight(1f),
            ) {
                if (busy) {
                    ButtonSpinner()
                } else {
                    Text(
                        stringResource(
                            if (step < steps) R.string.action_next else R.string.action_finish
                        )
                    )
                }
            }
        }
    }
}
