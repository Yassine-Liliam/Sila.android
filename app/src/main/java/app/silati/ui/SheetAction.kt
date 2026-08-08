package app.silati.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.silati.R

/**
 * State shared by every detail sheet that can act: one in-flight flag and one message.
 *
 * Kept here rather than in each screen so the three action sheets behave identically —
 * buttons disable while a request is out, and a failure shows the backend's own wording
 * (a 409 "already confirmed" reads better than anything we'd invent).
 */
class SheetActionState {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun rememberSheetActionState() = remember { SheetActionState() }

/** The error line under a sheet's buttons. Renders nothing when there's no error. */
@Composable
fun SheetError(message: String?) {
    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

/** A row of sheet buttons with consistent spacing. RowScope so buttons can take weight. */
@Composable
fun SheetActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Shown inside a button while its request is in flight. */
@Composable
fun ButtonSpinner() {
    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
}

/**
 * Confirmation for an action that can't be undone from here.
 *
 * Used for cancelling an order: the assistant can't reverse it and neither can this screen,
 * so a mis-tap would mean fixing it on the web.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_back)) }
        },
    )
}
