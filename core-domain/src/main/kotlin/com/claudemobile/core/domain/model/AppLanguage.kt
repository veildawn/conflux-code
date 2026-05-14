package com.claudemobile.core.domain.model

import java.util.Locale

/**
 * User preference for the app's display language.
 *
 * - [FOLLOW_SYSTEM] defers to the device system locale (resolved at runtime).
 * - [ENGLISH] and [SIMPLIFIED_CHINESE] pin the app to an explicit language.
 */
public enum class AppLanguage {
    FOLLOW_SYSTEM,
    ENGLISH,
    SIMPLIFIED_CHINESE;

    public companion object {
        /** Default value for new installations (Req 5.4). */
        public val DEFAULT: AppLanguage = FOLLOW_SYSTEM
    }
}

/**
 * BCP-47 tag for each supported explicit language. Kept separate from
 * the enum so the resolver owns all Locale-construction logic.
 */
public object SupportedLocales {
    public val ENGLISH: Locale = Locale.forLanguageTag("en")
    public val SIMPLIFIED_CHINESE: Locale = Locale.forLanguageTag("zh-CN")

    /** Set of language subtags the app has string resources for. */
    public val SUPPORTED_LANGUAGE_TAGS: Set<String> = setOf("en", "zh")
}
