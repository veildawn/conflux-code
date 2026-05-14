# Requirements Document

## Introduction

This feature adds internationalization (i18n) support to the ClaudeMobile Android application, enabling users to switch the app's display language between Chinese (Simplified) and English. The language preference is persisted across app restarts and applied without requiring an app restart. The implementation leverages Android's standard resource qualification system (`values/`, `values-zh/`) combined with a Compose-level locale override managed through the existing SettingsStore infrastructure.

## Glossary

- **Language_Switcher**: The UI component in the Settings screen that allows users to select their preferred display language
- **Locale_Manager**: The component responsible for applying the selected locale to the application context and Compose UI tree
- **Settings_Store**: The existing persistent preference store (DataStore-backed) that holds user preferences including the language selection
- **App_Locale**: The currently active locale used to resolve string resources throughout the application
- **System_Locale**: The device-level locale configured in Android system settings

## Requirements

### Requirement 1: Language Preference Persistence

**User Story:** As a user, I want my language preference to be saved, so that the app remembers my choice after I close and reopen it.

#### Acceptance Criteria

1. WHEN the user selects a language from the options (English, Simplified Chinese, Follow System), THE Settings_Store SHALL persist the selected language preference to disk within 500 milliseconds of selection
2. WHEN the app launches and a previously saved language preference exists, THE Settings_Store SHALL emit the saved language preference before the first UI frame is rendered
3. IF the Settings_Store fails to read the persisted language preference OR no saved preference exists, THEN THE Settings_Store SHALL fall back to "Follow System" as the default language preference
4. IF the active language preference is "Follow System" AND the System_Locale does not match a supported language (English or Simplified Chinese), THEN THE Settings_Store SHALL resolve the language to English

### Requirement 2: Language Switching Without Restart

**User Story:** As a user, I want the app language to change immediately when I select a new language, so that I do not need to restart the app.

#### Acceptance Criteria

1. WHEN the user selects a new language in the Language_Switcher, THE Locale_Manager SHALL apply the new locale to all visible Compose UI text and string-resource-backed content within 1 second, without Activity recreation
2. WHEN the locale changes, THE Locale_Manager SHALL update the Android Configuration context so that string resources resolve to the newly selected language
3. WHEN the locale changes, THE Locale_Manager SHALL trigger recomposition of all screens in the navigation back stack so that previously visited screens display the updated language when the user navigates back to them
4. IF the Locale_Manager fails to apply the new locale to the Configuration context, THEN THE Locale_Manager SHALL retain the previously active locale and display an error message indicating the language change was unsuccessful

### Requirement 3: Language Switcher UI

**User Story:** As a user, I want a clear language selection control in the Settings screen, so that I can easily switch between Chinese and English.

#### Acceptance Criteria

1. THE Language_Switcher SHALL display the available language options: "Follow System", "English", and "简体中文" (Simplified Chinese)
2. THE Language_Switcher SHALL indicate the currently active language with a checkmark or radio-button selection indicator visible to the user
3. WHEN the user taps a language option, THE Language_Switcher SHALL trigger the language change and all UI text SHALL update within 1 second
4. THE Language_Switcher SHALL display each language name in its own native script (English as "English", Chinese as "简体中文")
5. THE Language_Switcher SHALL be accessible via a "Language" or "语言" menu item in the Settings screen

### Requirement 4: String Resource Organization

**User Story:** As a developer, I want all user-facing strings externalized into resource files, so that adding new languages in the future is straightforward.

#### Acceptance Criteria

1. THE App SHALL define English strings in `res/values/strings.xml` as the default resource
2. THE App SHALL define Chinese strings in `res/values-zh-rCN/strings.xml` as the Chinese resource
3. THE App SHALL resolve string resources based on the App_Locale rather than the System_Locale
4. WHEN a string key is missing from the Chinese resource file, THE App SHALL fall back to the English default string
5. THE App SHALL reference all user-facing text via Android string resource identifiers, with no hardcoded user-facing strings in Kotlin source or Compose UI code
6. WHEN a string resource contains format arguments, THE App SHALL use positional format specifiers (e.g., `%1$s`, `%2$d`) so that translators can safely reorder parameters

### Requirement 5: System Language Follow Mode

**User Story:** As a user, I want the option to follow my device's system language, so that the app language matches my phone settings automatically.

#### Acceptance Criteria

1. THE Language_Switcher SHALL provide a "Follow System" option in addition to explicit language choices
2. WHEN "Follow System" is selected, THE Locale_Manager SHALL resolve string resources using the device's current System_Locale
3. WHILE "Follow System" is active, WHEN the device system language changes, THE Locale_Manager SHALL update the App_Locale to match the new System_Locale before the next UI composition pass
4. THE Settings_Store SHALL use "Follow System" as the default language preference for new installations
5. IF "Follow System" is active and the System_Locale does not match any of the app's supported locales, THEN THE Locale_Manager SHALL fall back to English as the App_Locale
6. WHEN the user switches from "Follow System" to an explicit language choice, THE Locale_Manager SHALL immediately apply the selected locale as the App_Locale

### Requirement 6: Locale-Aware Formatting

**User Story:** As a user, I want dates, numbers, and other formatted content to match my selected language, so that the app feels natural in my chosen language.

#### Acceptance Criteria

1. WHEN the App_Locale is set to Chinese, THE App SHALL format dates using the pattern yyyy年M月d日 (e.g., 2024年3月5日)
2. WHEN the App_Locale is set to English, THE App SHALL format dates using the pattern MMM d, yyyy (e.g., Mar 5, 2024)
3. WHEN the App_Locale is set to Chinese, THE App SHALL format numbers using a period as the decimal separator and a comma as the thousands grouping separator (e.g., 1,234.56)
4. WHEN the App_Locale is set to English, THE App SHALL format numbers using a period as the decimal separator and a comma as the thousands grouping separator (e.g., 1,234.56)
5. THE App SHALL apply the App_Locale to all number and date formatting operations throughout the UI, updating displayed values when the App_Locale changes without requiring a restart

### Requirement 7: RTL/Layout Consistency

**User Story:** As a user, I want the app layout to remain consistent when switching between Chinese and English, so that the interface stays predictable.

#### Acceptance Criteria

1. THE App SHALL maintain left-to-right (LTR) layout direction for both English and Chinese locales
2. WHEN the language changes, THE App SHALL preserve the current navigation route and back stack so that the user remains on the same screen at the same navigation depth
3. WHEN the language changes while a scrollable list or chat view is displayed, THE App SHALL restore the scroll position to within 1 item of the position held before the change
4. WHEN the language changes while the user has unsaved text input in a text field, THE App SHALL retain the text content and cursor position in that field
5. IF the language change triggers an Activity recreation, THEN THE App SHALL complete the recreation and display the restored screen within 2 seconds on the reference device
