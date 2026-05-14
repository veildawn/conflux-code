package com.claudemobile.core.domain.i18n

import com.claudemobile.core.domain.model.AppLanguage
import com.claudemobile.core.domain.model.SupportedLocales
import java.util.Locale

/**
 * Pure mapping from (user preference, system locale) to effective [Locale].
 *
 * Separated from the persistence layer so it can be called from both the
 * Compose layer (via `ProvideAppLocale`) and unit tests without any
 * Android dependencies.
 */
public object LocaleResolver {

    /**
     * Returns the [Locale] the app should render with, given the current
     * user [preference] and the current [systemLocale].
     *
     * - For [AppLanguage.ENGLISH] / [AppLanguage.SIMPLIFIED_CHINESE], returns
     *   the pinned locale and ignores [systemLocale] entirely.
     * - For [AppLanguage.FOLLOW_SYSTEM], returns [SupportedLocales.SIMPLIFIED_CHINESE]
     *   when the system locale's language subtag is `zh`, [SupportedLocales.ENGLISH]
     *   when it is `en`, and falls back to [SupportedLocales.ENGLISH] otherwise
     *   (per Requirements 1.4 and 5.5).
     *
     * This function is total: it returns a value for every possible input
     * and never throws. The returned [Locale] is always one of the values
     * exposed on [SupportedLocales].
     */
    public fun resolve(
        preference: AppLanguage,
        systemLocale: Locale,
    ): Locale = when (preference) {
        AppLanguage.ENGLISH -> SupportedLocales.ENGLISH
        AppLanguage.SIMPLIFIED_CHINESE -> SupportedLocales.SIMPLIFIED_CHINESE
        AppLanguage.FOLLOW_SYSTEM -> {
            val language = systemLocale.language.lowercase(Locale.ROOT)
            when (language) {
                "zh" -> SupportedLocales.SIMPLIFIED_CHINESE
                "en" -> SupportedLocales.ENGLISH
                else -> SupportedLocales.ENGLISH // Req 1.4, 5.5
            }
        }
    }
}
