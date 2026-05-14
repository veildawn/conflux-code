# Implementation Plan: i18n Language Switching

## Overview

This plan implements the i18n-language-switching feature by layering from the pure core outward:
first the `AppLanguage` enum and `LocaleResolver` in `core-domain`, then DataStore persistence in
`core-data`, then the `ProvideAppLocale` Compose wrapper and locale-aware formatters in `core-ui`,
then the language switcher UI and ViewModel action in `feature-settings`, and finally wiring in
`MainActivity` and localized string resources in `app` and feature modules. Property-based tests
(Kotest) are colocated with each piece of pure logic; Compose-rule and instrumented tests cover the
UI and end-to-end wiring.

## Tasks

- [ ] 1. Add `AppLanguage` enum and `SupportedLocales` to `core-domain`
  - [x] 1.1 Create `AppLanguage` enum and `SupportedLocales` object
    - Add `com.claudemobile.core.domain.model.AppLanguage` with values `FOLLOW_SYSTEM`, `ENGLISH`, `SIMPLIFIED_CHINESE` and a companion `DEFAULT = FOLLOW_SYSTEM`
    - Add `com.claudemobile.core.domain.model.SupportedLocales` holding `ENGLISH = Locale.forLanguageTag("en")`, `SIMPLIFIED_CHINESE = Locale.forLanguageTag("zh-CN")`, and `SUPPORTED_LANGUAGE_TAGS = setOf("en", "zh")`
    - _Requirements: 5.4, 1.4, 5.5, 5.6_

- [x] 2. Implement `LocaleResolver` in `core-domain`
  - [x] 2.1 Create `LocaleResolver.resolve(preference, systemLocale)` pure function
    - New file `com.claudemobile.core.domain.i18n.LocaleResolver`
    - Explicit preferences pin to their `SupportedLocales` value; `FOLLOW_SYSTEM` maps `zh` → `SIMPLIFIED_CHINESE`, `en` → `ENGLISH`, otherwise → `ENGLISH`
    - No Android dependencies; pure Kotlin/`java.util.Locale`
    - _Requirements: 2.2, 5.2, 5.6, 1.4, 5.5_

  - [ ]* 2.2 Property test: `LocaleResolver` is deterministic
    - **Property 1: LocaleResolver is a pure, deterministic function**
    - Kotest `checkAll` with `Arb.enum<AppLanguage>()` × custom `Arb<Locale>`; assert `resolve(p, s) == resolve(p, s)` across ≥100 iterations
    - **Validates: Requirements 2.2, 5.2**

  - [ ]* 2.3 Property test: `LocaleResolver` is total and returns supported locales
    - **Property 2: LocaleResolver is total and returns only supported locales**
    - `Arb<Locale>` mixes language tags from `{en, zh, ja, fr, de, "", "xx"}` with arbitrary countries/variants; assert the result is one of `SupportedLocales.ENGLISH`/`SupportedLocales.SIMPLIFIED_CHINESE` and never throws
    - **Validates: Requirements 1.4, 5.5**

  - [ ]* 2.4 Property test: `FOLLOW_SYSTEM` falls back to English for unsupported locales
    - **Property 3: FOLLOW_SYSTEM falls back to English for unsupported system locales**
    - Generate `Locale`s whose language subtag is not in `SUPPORTED_LANGUAGE_TAGS`; assert `resolve(FOLLOW_SYSTEM, s) == SupportedLocales.ENGLISH`
    - **Validates: Requirements 1.4, 5.5**

  - [ ]* 2.5 Property test: explicit preferences ignore the system locale
    - **Property 4: Explicit preferences ignore the system locale**
    - For any `Locale` `s`, assert `resolve(ENGLISH, s) == SupportedLocales.ENGLISH` and `resolve(SIMPLIFIED_CHINESE, s) == SupportedLocales.SIMPLIFIED_CHINESE`
    - **Validates: Requirements 5.6, 3.1, 3.2**

- [ ] 3. Extend `AppSettings`, `SettingsStore`, and `PreferenceKeys` in `core-domain`
  - [x] 3.1 Add `appLanguage` field and setter
    - Add `appLanguage: AppLanguage = AppLanguage.DEFAULT` to `AppSettings`
    - Add `suspend fun setAppLanguage(language: AppLanguage)` to the `SettingsStore` interface
    - Add `APP_LANGUAGE = "app_language"` to `PreferenceKeys`
    - _Requirements: 1.1, 1.2, 5.4_

- [x] 4. Extend `SettingsStoreImpl` with `appLanguage` persistence in `core-data`
  - [x] 4.1 Implement DataStore read/write with default fallback
    - Add `KEY_APP_LANGUAGE = stringPreferencesKey(PreferenceKeys.APP_LANGUAGE)`
    - In the settings `Flow` builder, read `prefs[KEY_APP_LANGUAGE]` and map via `toAppLanguageOrDefault()` (catches `IllegalArgumentException` for unknown enum names)
    - Implement `setAppLanguage` using `dataStore.edit { it[KEY_APP_LANGUAGE] = language.name }`
    - Ensure the existing `IOException` `catch { }` on the flow emits `AppSettings()` defaults when read fails
    - _Requirements: 1.1, 1.2, 1.3, 5.4_

  - [ ]* 4.2 Property test: persistence round-trip
    - **Property 5: Persistence round-trip**
    - Back `SettingsStoreImpl` with a scratch-directory `DataStore<Preferences>`; `checkAll(Arb.enum<AppLanguage>())` — for each value, call `setAppLanguage`, collect the first emission, assert `appSettings.appLanguage == x`
    - **Validates: Requirements 1.1, 1.2**

  - [ ]* 4.3 Property test: unknown persisted value resilience
    - **Property 6: Unknown persisted values resolve to the default**
    - `checkAll(Arb.string())` — write an arbitrary string directly under `KEY_APP_LANGUAGE`, assert the flow emits `AppSettings.appLanguage == AppLanguage.DEFAULT` without throwing
    - **Validates: Requirements 1.3**

  - [ ]* 4.4 Property test: fresh install default is `FOLLOW_SYSTEM`
    - **Property 7: Default language is FOLLOW_SYSTEM on fresh installs**
    - With an empty DataStore (no `APP_LANGUAGE` key), assert the first emission of `settings` has `appLanguage == AppLanguage.FOLLOW_SYSTEM`
    - **Validates: Requirements 1.3, 5.4**

  - [ ]* 4.5 Example test: write-to-emission latency under 500 ms
    - Using a test dispatcher and scratch DataStore, measure the time between `setAppLanguage` and observing the new emission on `settings`
    - Use generous CI slack (e.g. assert < 500 ms on the reference test runner)
    - _Requirements: 1.1_

- [x] 5. Checkpoint — domain and persistence
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Add `ProvideAppLocale` and `LocalAppLocale` to `core-ui`
  - [x] 6.1 Implement `LocalAppLocale` CompositionLocal and `ProvideAppLocale` composable
    - New file `com.claudemobile.core.ui.i18n.ProvideAppLocale`
    - Reads `LocalConfiguration.current.locales[0]` for the system locale, calls `LocaleResolver.resolve`, builds a `Configuration(systemConfiguration).apply { setLocale(effective); setLayoutDirection(effective) }`, and wraps `baseContext.createConfigurationContext(...)`
    - `CompositionLocalProvider` overrides `LocalAppLocale`, `LocalConfiguration`, and `LocalContext`
    - Handle `createConfigurationContext` returning `null` by retaining the previous context and exposing a one-shot error signal (shared state flow or `LaunchedEffect` callback) the caller can route to a snackbar
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 5.3, 7.1_

  - [ ]* 6.2 Compose unit test: `ProvideAppLocale` swaps the resolved `stringResource`
    - Use `createComposeRule()`; render a `Text(stringResource(R.string.some_test_key))` under `ProvideAppLocale(ENGLISH)` and under `ProvideAppLocale(SIMPLIFIED_CHINESE)`; assert the displayed text matches the English and Chinese values from a test `values/` + `values-zh-rCN/` set
    - _Requirements: 2.1, 2.2, 4.3_

  - [ ]* 6.3 Compose unit test: `ProvideAppLocale` preserves LTR layout direction
    - Assert `LocalLayoutDirection.current == LayoutDirection.Ltr` under both `ENGLISH` and `SIMPLIFIED_CHINESE`
    - _Requirements: 7.1_

- [x] 7. Add locale-aware formatters to `core-ui`
  - [x] 7.1 Implement `datePatternFor`, `rememberDateFormatter`, `rememberNumberFormat`
    - New file `com.claudemobile.core.ui.i18n.AppLocaleFormatters`
    - `datePatternFor(locale)` returns `"yyyy年M月d日"` if `locale.language.equals("zh", ignoreCase = true)`, otherwise `"MMM d, yyyy"`
    - `rememberDateFormatter()` reads `LocalAppLocale.current` and returns `DateTimeFormatter.ofPattern(pattern, locale)`
    - `rememberNumberFormat()` returns `NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = true }`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [ ]* 7.2 Property test: date pattern is locale-dependent
    - **Property 8: Date pattern is locale-dependent**
    - `checkAll(Arb<Locale>())` — assert `datePatternFor(l) == "yyyy年M月d日"` iff `l.language.equals("zh", ignoreCase = true)`, else `"MMM d, yyyy"`
    - **Validates: Requirements 6.1, 6.2**

  - [ ]* 7.3 Unit test: number format groups with comma and decimal point under both app locales
    - Format `1234.56` under English and Simplified Chinese `Locale`s; assert both produce `"1,234.56"`
    - _Requirements: 6.3, 6.4_

- [x] 8. Checkpoint — core-ui
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Add `SettingsAction.SetAppLanguage` and `SettingsViewModel` wiring in `feature-settings`
  - [x] 9.1 Add action, ViewModel handler, and write-failure error channel
    - Add `data class SetAppLanguage(val language: AppLanguage) : SettingsAction`
    - Add `is SettingsAction.SetAppLanguage -> setAppLanguage(action.language)` branch
    - Implement `private fun setAppLanguage(language: AppLanguage)` using `viewModelScope.launch { try { settingsStore.setAppLanguage(language) } catch (e: Throwable) { /* emit snackbar error event, retain prior preference */ } }`
    - Expose the error event via the existing one-shot error flow/channel used by the Settings UI (match the current pattern)
    - _Requirements: 1.1, 2.4, 3.3_

- [x] 10. Add `LanguageSection` UI in `feature-settings`
  - [x] 10.1 Implement `LanguageSection` composable in `SettingsScreen`
    - Vertical `Column` with a section title (`stringResource(R.string.settings_language_title)`) and a selectable `Row` per `AppLanguage.entries` value containing a `RadioButton` plus label
    - Labels: `FOLLOW_SYSTEM` → `stringResource(R.string.settings_language_follow_system)`; `ENGLISH` → hardcoded `"English"`; `SIMPLIFIED_CHINESE` → hardcoded `"简体中文"` (native-script, not translated per Req 3.4)
    - Use `Modifier.selectable(selected, role = Role.RadioButton, onClick)` for a11y; pass `onClick = null` on the `RadioButton` itself
    - Insert the section in `SettingsScreenContent` in the location specified by the design
    - Read `current: AppLanguage` from the Settings UI state; dispatch `SettingsAction.SetAppLanguage(lang)`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [ ]* 10.2 Compose UI test: renders three options with selection indicator and triggers action
    - `createComposeRule()` renders `LanguageSection(current = ENGLISH, onLanguageChange = capture)`
    - Assert nodes for `"Follow System"`, `"English"`, `"简体中文"` exist; the `ENGLISH` option `assertIsSelected()`
    - Click `"简体中文"`; assert `capture` received `SIMPLIFIED_CHINESE`
    - _Validates: Requirements 3.1, 3.2, 3.3, 3.4_

  - [ ]* 10.3 Compose UI test: native-script labels render identically under both app locales
    - Parameterize over `ProvideAppLocale(ENGLISH)` and `ProvideAppLocale(SIMPLIFIED_CHINESE)`; assert both renderings show `"English"` and `"简体中文"` (labels are not re-translated)
    - _Validates: Requirements 3.4_

- [x] 11. Externalize user-facing strings to Android resource files
  - [x] 11.1 Add English string resources and audit user-facing strings for positional format specifiers
    - Add `settings_language_title` (`"Language"`) and `settings_language_follow_system` (`"Follow System"`) to `feature-settings/src/main/res/values/strings.xml` (and any other module that hosts the Settings screen strings)
    - Sweep the codebase for any remaining hardcoded user-facing strings in Kotlin/Compose sources; move them into the appropriate module's `values/strings.xml`
    - Ensure every formatted string uses positional specifiers (`%1$s`, `%2$d`) per Req 4.6
    - _Requirements: 4.1, 4.5, 4.6_

  - [x] 11.2 Create `values-zh-rCN/strings.xml` translations across app and feature modules
    - Create `app/src/main/res/values-zh-rCN/strings.xml` and per-feature-module `values-zh-rCN/strings.xml` files mirroring the keys in each module's `values/strings.xml`
    - Translate every user-facing key added in 11.1 (e.g. `settings_language_title` → `"语言"`, `settings_language_follow_system` → `"跟随系统"`)
    - Preserve positional format specifiers exactly as in the English source
    - _Requirements: 4.2, 4.4_

- [x] 12. Wire `ProvideAppLocale` into `MainActivity`
  - [x] 12.1 Wrap `AppNavGraph` in `ProvideAppLocale` using `settings.appLanguage`
    - In `MainActivity.setContent`, collect `settings` via `settingsStore.settings.collectAsState(initial = AppSettings())`
    - Nest `ProvideAppLocale(appLanguage = settings.appLanguage) { ... }` inside `ClaudeMobileTheme { ... }` so it wraps the `Scaffold` + `AppNavGraph`
    - Do NOT declare `android:configChanges="locale|layoutDirection"` on `MainActivity` (design decision — system locale changes rely on default Activity recreation)
    - Route the `ProvideAppLocale` error signal (from 6.1) and the `SettingsViewModel` write-failure event to the existing app-level snackbar host
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 4.3, 5.3, 7.2, 7.3, 7.4_

  - [ ]* 12.2 Instrumented test: explicit language change without Activity recreation preserves state
    - Launch `MainActivity`; navigate to a chat list and scroll to item N; type text into an input field; navigate to Settings and switch language from English to Simplified Chinese
    - Assert: the `Activity` instance identity is unchanged; a known `stringResource`-backed label has re-rendered in Chinese; scroll position is within 1 item of N; the input-field text and cursor position are intact
    - _Requirements: 2.1, 7.2, 7.3, 7.4_

  - [ ]* 12.3 Instrumented test: system locale change under `FOLLOW_SYSTEM` triggers recreation within 2 s
    - With `appLanguage = FOLLOW_SYSTEM`, programmatically change the app locale via `AppCompatDelegate.setApplicationLocales` (or equivalent test API); assert `MainActivity` is alive and shows the new locale within 2 seconds
    - _Requirements: 5.3, 7.5_

  - [ ]* 12.4 Instrumented test: unsupported system locale under `FOLLOW_SYSTEM` shows English
    - Set the app locale to `ja-JP` with `appLanguage = FOLLOW_SYSTEM`; assert visible strings are English (e.g. `settings_language_title` renders as `"Language"`)
    - _Requirements: 1.4, 5.5_

- [x] 13. Final checkpoint — full end-to-end
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Sub-tasks marked with `*` are optional test tasks; they can be skipped for a faster MVP, but all non-optional tasks must be implemented.
- The design document defines eight correctness properties; each has its own property-test sub-task (2.2–2.5, 4.2–4.4, 7.2) and references the specific requirements it validates.
- Property-based tests use Kotest (already on the classpath) with ≥ 100 iterations per property and include a comment header of the form `// Feature: i18n-language-switching, Property <N>: <property text>`.
- Compose-rule tests cover UI state (selection indicator, labels, action dispatch) and locale-scoped resource resolution; instrumented tests cover end-to-end behavior that cannot be expressed in unit tests (Activity recreation, system-locale changes).
- Checkpoints are placed after each major layer (core-domain + core-data, core-ui, and final wiring) so bugs surface before they compound.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "11.1"] },
    { "id": 1, "tasks": ["2.1", "3.1", "11.2"] },
    { "id": 2, "tasks": ["2.2", "2.3", "2.4", "2.5", "4.1", "6.1", "9.1"] },
    { "id": 3, "tasks": ["4.2", "4.3", "4.4", "4.5", "6.2", "6.3", "7.1", "10.1", "12.1"] },
    { "id": 4, "tasks": ["7.2", "7.3", "10.2", "10.3", "12.2", "12.3", "12.4"] }
  ]
}
```
