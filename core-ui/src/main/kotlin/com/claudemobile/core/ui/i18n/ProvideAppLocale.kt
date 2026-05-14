package com.claudemobile.core.ui.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.claudemobile.core.domain.i18n.LocaleResolver
import com.claudemobile.core.domain.model.AppLanguage
import com.claudemobile.core.ui.R
import java.util.Locale

/**
 * CompositionLocal exposing the app's currently effective [Locale].
 *
 * Consumers (e.g. date/number formatters, custom text layout) read this
 * instead of the platform's `LocalConfiguration.current.locales`, so that
 * formatting tracks the *app* locale rather than the system locale when
 * the two differ.
 */
public val LocalAppLocale: androidx.compose.runtime.ProvidableCompositionLocal<Locale> =
    compositionLocalOf { Locale.getDefault() }

/**
 * Callback type for locale-application errors. Callers can route this
 * to a snackbar or other error-display mechanism.
 */
public typealias OnLocaleError = (message: String) -> Unit

/**
 * Wraps [content] so that Android resources, `stringResource(...)`, and
 * [LocalAppLocale] all resolve against the locale derived from the given
 * [appLanguage] preference and the current system locale.
 *
 * Implementation note: overriding `LocalConfiguration` alone is insufficient
 * — `stringResource` ultimately reads from `LocalContext.current.resources`.
 * We therefore also provide a locale-configured [Context] via
 * [Context.createConfigurationContext], and both CompositionLocals are
 * overridden in the same provider so they stay consistent.
 *
 * If [Context.createConfigurationContext] returns `null` (extremely rare;
 * some heavily modified OEM ROMs), the previous context is retained and
 * [onLocaleError] is invoked once so the caller can route the error to a
 * snackbar.
 *
 * @param appLanguage The user's language preference from [SettingsStore].
 * @param onLocaleError Optional callback invoked once when locale application
 *   fails. The message is suitable for display in a snackbar.
 * @param content The composable subtree that should render with the resolved locale.
 */
@Composable
public fun ProvideAppLocale(
    appLanguage: AppLanguage,
    onLocaleError: OnLocaleError? = null,
    content: @Composable () -> Unit,
) {
    val systemConfiguration = LocalConfiguration.current
    val baseContext = LocalContext.current

    // The system locale is the *first* entry in the locale list of the
    // configuration Compose gives us. Reading it inside composition means
    // recomposition happens automatically when the OS delivers a new
    // configuration (Req 5.3).
    val systemLocale: Locale = systemConfiguration.locales[0] ?: Locale.getDefault()

    val effectiveLocale: Locale = remember(appLanguage, systemLocale) {
        LocaleResolver.resolve(appLanguage, systemLocale)
    }

    // Build a new Configuration with the effective locale and a context
    // that resolves resources against it.
    val localizedConfiguration: Configuration = remember(systemConfiguration, effectiveLocale) {
        Configuration(systemConfiguration).apply {
            setLocale(effectiveLocale)
            setLayoutDirection(effectiveLocale) // Req 7.1 — zh/en both LTR
        }
    }

    // Track whether createConfigurationContext failed, so we can fire
    // the error callback exactly once per failure.
    var localeError by remember { mutableStateOf(false) }

    // We must NOT use createConfigurationContext() here because it returns a
    // plain ContextImpl that is not an Activity. Hilt's hiltViewModel() and
    // other Compose integrations require LocalContext to be an Activity.
    // Instead, wrap the existing context (which IS the Activity) in a
    // ContextThemeWrapper that applies the localized configuration. This
    // preserves the Activity type while overriding resources.
    val localizedContext: Context = remember(baseContext, localizedConfiguration) {
        try {
            android.view.ContextThemeWrapper(baseContext, baseContext.theme).apply {
                applyOverrideConfiguration(localizedConfiguration)
            }
        } catch (_: Exception) {
            localeError = true
            baseContext
        }
    }

    // Fire the error callback as a one-shot side effect when locale
    // application fails.
    if (localeError && onLocaleError != null) {
        val errorMessage = stringResource(R.string.core_ui_locale_error)
        LaunchedEffect(Unit) {
            onLocaleError(errorMessage)
            localeError = false
        }
    }

    CompositionLocalProvider(
        LocalAppLocale provides effectiveLocale,
        LocalConfiguration provides localizedConfiguration,
        LocalContext provides localizedContext,
    ) {
        content()
    }
}
