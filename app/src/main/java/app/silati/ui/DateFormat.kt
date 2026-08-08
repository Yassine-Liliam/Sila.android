package app.silati.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Turning the API's ISO-8601 strings into something a person reads.
 *
 * The backend serialises every timestamp with `toISOString()`, so the wire format is always
 * UTC with a `Z`. Nothing is parsed into a date type on the way in — the app has no reason
 * to do date arithmetic, only to display — so parsing happens here, at the point of use.
 *
 * `java.time` is available unguarded: minSdk is 26.
 */

private fun parse(iso: String?): Instant? =
    iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * "2 hours ago", "Yesterday", "8 Aug" — for list rows, where the question is *how recent*
 * rather than exactly when.
 *
 * `DateUtils` is localised by the platform, so this reads correctly in French and Arabic
 * without a single string of ours. Rolling our own would mean re-inventing plurals in three
 * languages.
 */
fun relativeTime(iso: String?): String? = parse(iso)?.let {
    DateUtils.getRelativeTimeSpanString(
        it.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS, // "0 seconds ago" is never useful; floor at a minute
    ).toString()
}

/** Date and time, in the reader's locale and their own timezone — for detail screens. */
@Composable
fun dateTime(iso: String?): String? = formatted(iso, FormatStyle.MEDIUM, FormatStyle.SHORT)

/** Date only, for anything where the hour is noise (e.g. "client since"). */
@Composable
fun date(iso: String?): String? = formatted(iso, FormatStyle.MEDIUM, null)

@Composable
private fun formatted(iso: String?, dateStyle: FormatStyle, timeStyle: FormatStyle?): String? {
    // Read through the context rather than a cached default: a per-app language change
    // (Android 13+ Settings) recreates the activity with a new configuration.
    val locale = LocalContext.current.resources.configuration.locales[0]
    return parse(iso)?.let {
        val formatter = if (timeStyle == null) {
            DateTimeFormatter.ofLocalizedDate(dateStyle)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(dateStyle, timeStyle)
        }
        formatter.withLocale(locale).withZone(ZoneId.systemDefault()).format(it)
    }
}
