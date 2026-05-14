package com.claudemobile.core.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Returns a locale-aware date formatter for the currently active app locale.
 *
 * - For Simplified Chinese: pattern `yyyy年M月d日` (Req 6.1).
 * - For English: pattern `MMM d, yyyy` (Req 6.2).
 */
@Composable
public fun rememberDateFormatter(): DateTimeFormatter {
    val locale = LocalAppLocale.current
    return remember(locale) {
        DateTimeFormatter.ofPattern(datePatternFor(locale), locale)
    }
}

/**
 * Returns the date format pattern appropriate for the given [locale].
 *
 * Chinese locales use the year-month-day pattern with CJK characters;
 * all other locales use the abbreviated English month format.
 */
internal fun datePatternFor(locale: Locale): String = when {
    locale.language.equals("zh", ignoreCase = true) -> "yyyy年M月d日"
    else -> "MMM d, yyyy"
}

/**
 * Returns a locale-aware [NumberFormat] for the currently active app locale.
 *
 * Per Req 6.3/6.4, both English and Simplified Chinese use `.` as the
 * decimal separator and `,` as the thousands separator. Grouping is
 * always enabled.
 */
@Composable
public fun rememberNumberFormat(): NumberFormat {
    val locale = LocalAppLocale.current
    return remember(locale) {
        NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = true }
    }
}
