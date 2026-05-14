# Requirements Document

## Introduction

This feature replaces the current single "Configure Anthropic API Key" setting in the Android Claude Mobile Client with a richer provider-selection flow. Users select a provider from a list of built-in presets backed by Anthropic-compatible endpoints (GLM Coding Plan, MiniMax Token Plan, Kimi Code Plan) and enter only an API key, or they select a "Custom / Anthropic-compatible" option and enter their own base URL, API key, and model name. The selected configuration is passed to the Claude_CLI child process via the standard Anthropic environment variables (`ANTHROPIC_BASE_URL`, `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_MODEL`, and optionally `ANTHROPIC_SMALL_FAST_MODEL`) at spawn time.

This feature extends and, where indicated, supersedes the following requirements of the existing `android-claude-termux-client` spec (`.kiro/specs/android-claude-termux-client/requirements.md`):

- **Supersedes Requirement 2, AC 3**: the Bridge SHALL now set `ANTHROPIC_BASE_URL`, one of `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN`, and `ANTHROPIC_MODEL` based on the Active_Profile, in addition to the existing `HOME`, `PATH`, `TERM`, `LANG`.
- **Supersedes Requirement 6 (Credential_Store)**: the Credential_Store now holds zero or more Provider_Profiles rather than a single Anthropic API key; all existing security guarantees (Android Keystore backing, masked display, never logged) apply to every profile.
- **Supersedes Requirement 9, AC 1**: the `selected Claude model identifier` preference is replaced by an Active_Profile reference; the effective model is derived from the Active_Profile.
- **Extends Requirement 13, AC 3 and AC 5**: diagnostics redaction now redacts every stored API key across every Provider_Profile, not just a single key.

All terms, security guarantees, and components not explicitly redefined here retain the meaning assigned by the `android-claude-termux-client` spec.

## Glossary

- **Provider_Preset**: A built-in, code-defined description of a supported AI provider that exposes an Anthropic-compatible API. A Provider_Preset declares at minimum a stable `presetId`, a localized `displayName`, a fixed `baseUrl`, a `defaultModel` identifier, an optional `defaultSmallFastModel` identifier, and the `authHeaderStyle` (`apiKey` or `authToken`) the provider expects.
- **Provider_Registry**: The component that exposes the set of available Provider_Presets to the rest of the App. The Provider_Registry is shipped in-app with every release and is updated via app updates; the App does not fetch presets at runtime.
- **Provider_Profile**: A user-owned configuration record that binds a Provider_Preset (or the Custom_Provider sentinel) to a user-supplied API key, a user-overridable `model` identifier, and an optional user-overridable `smallFastModel` identifier. Each Provider_Profile has a unique `profileId`, a user-editable `displayName`, a reference to its originating Provider_Preset (or `custom`), a `baseUrl`, an `apiKey`, a `model`, an optional `smallFastModel`, and `createdAt` / `updatedAt` timestamps.
- **Custom_Provider**: The sentinel value for a Provider_Profile that is not derived from a Provider_Preset; the user supplies `baseUrl`, `apiKey`, and `model` directly.
- **Active_Profile**: The single Provider_Profile currently selected as the source of environment values for Claude_CLI launches. At most one Provider_Profile is the Active_Profile at any time.
- **Provider_Profile_Store**: The persistence component that stores Provider_Profiles and the identifier of the Active_Profile. The Provider_Profile_Store uses EncryptedSharedPreferences backed by the Android Keystore, consistent with the Credential_Store of the base spec.
- **Connection_Test**: A lightweight probe that validates a Provider_Profile's connectivity and credentials without starting a full Session. The Connection_Test produces one of the outcomes `ok`, `unauthorized`, `unreachable`, `invalid_url`, `invalid_model`, or `unknown_error` together with a user-readable reason.
- **Built-in Presets** (initial set shipped with this feature):
  - **GLM Coding Plan**: `presetId` = `glm_coding_plan`, `baseUrl` = `https://open.bigmodel.cn/api/anthropic`, `defaultModel` = `glm-4.6`, `authHeaderStyle` = `authToken`.
  - **MiniMax Token Plan**: `presetId` = `minimax_token_plan`, `baseUrl` = `https://api.minimaxi.com/anthropic`, `defaultModel` = `MiniMax-M2`, `authHeaderStyle` = `authToken`.
  - **Kimi Code Plan**: `presetId` = `kimi_code_plan`, `baseUrl` = `https://api.moonshot.cn/anthropic`, `defaultModel` = `kimi-k2-turbo-preview`, `authHeaderStyle` = `authToken`.

Exact `baseUrl` and `defaultModel` values for each preset are defined in code and are treated as implementation details subject to change in the design phase; the requirements in this document do not depend on specific literal values.

## Requirements

### Requirement 1: Provider Registry of Built-in Presets

**User Story:** As a User, I want the App to ship with a curated list of Anthropic-compatible providers, so that I can pick a plan and enter only my API key without looking up URLs or model names.

#### Acceptance Criteria

1. THE Provider_Registry SHALL expose at least the Provider_Presets `glm_coding_plan`, `minimax_token_plan`, and `kimi_code_plan` on every App launch.
2. THE Provider_Registry SHALL expose, for each Provider_Preset, a stable `presetId`, a localized `displayName`, a `baseUrl`, a `defaultModel`, an optional `defaultSmallFastModel`, and an `authHeaderStyle` of either `apiKey` or `authToken`.
3. THE Provider_Registry SHALL be populated from in-app resources only and SHALL NOT perform any network request to obtain preset definitions.
4. WHEN the User opens the provider selection screen, THE UI_Layer SHALL display every Provider_Preset exposed by the Provider_Registry together with a distinct entry for the Custom_Provider option.
5. WHERE a Provider_Preset declares a `defaultSmallFastModel`, THE UI_Layer SHALL pre-populate the corresponding field in the profile editor with that value.

### Requirement 2: Provider Profile Creation from a Preset

**User Story:** As a User, I want to create a Provider_Profile from a built-in preset by entering only my API key, so that I can get started quickly.

#### Acceptance Criteria

1. WHEN the User selects a Provider_Preset and submits a non-empty API key, THE Provider_Profile_Store SHALL persist a new Provider_Profile whose `baseUrl`, `model`, `smallFastModel`, and `authHeaderStyle` fields are copied from the Provider_Preset and whose `apiKey` is set to the submitted value.
2. THE Provider_Profile_Store SHALL assign the new Provider_Profile a `profileId` that is unique within the Provider_Profile_Store and a `displayName` defaulting to the Provider_Preset's `displayName`.
3. THE UI_Layer SHALL allow the User to override the `displayName`, `model`, and `smallFastModel` of a preset-derived Provider_Profile before the Provider_Profile is persisted.
4. IF the User submits an empty API key when creating a preset-derived Provider_Profile, THEN THE UI_Layer SHALL display an inline validation message and SHALL NOT invoke the Provider_Profile_Store.
5. WHEN the User submits the creation form for a preset-derived Provider_Profile, THE UI_Layer SHALL clear the API key input field from its state after the Provider_Profile_Store acknowledges persistence.

### Requirement 3: Custom Provider Profile Creation

**User Story:** As a User, I want to create a Provider_Profile against any Anthropic-compatible endpoint, so that I can use providers that are not in the built-in list.

#### Acceptance Criteria

1. WHEN the User selects the Custom_Provider option and submits the form, THE Provider_Profile_Store SHALL persist a new Provider_Profile whose preset reference is `custom` and whose `baseUrl`, `apiKey`, `model`, and optional `smallFastModel` fields are taken from the submitted values.
2. THE UI_Layer SHALL require the User to provide `displayName`, `baseUrl`, `apiKey`, and `model` for a Custom_Provider profile before submission is enabled.
3. WHILE the User is editing a Custom_Provider profile form, THE UI_Layer SHALL display a per-field validation state for each of `displayName`, `baseUrl`, `apiKey`, and `model` that indicates whether the individual field's current value satisfies its own validation rule, independently of whether the overall submission is enabled.
4. IF the submitted `baseUrl` is not a well-formed `https://` URL, THEN THE UI_Layer SHALL display an inline validation message identifying the URL field and SHALL NOT invoke the Provider_Profile_Store.
5. IF the submitted `apiKey` is empty, THEN THE UI_Layer SHALL display an inline validation message identifying the API key field and SHALL NOT invoke the Provider_Profile_Store.
6. IF the submitted `model` is empty, THEN THE UI_Layer SHALL display an inline validation message identifying the model field and SHALL NOT invoke the Provider_Profile_Store.
7. THE UI_Layer SHALL expose `authHeaderStyle` for Custom_Provider profiles with a default of `apiKey` and SHALL allow the User to choose `authToken` instead.

### Requirement 4: Editing, Deleting, and Listing Provider Profiles

**User Story:** As a User, I want to manage my stored Provider_Profiles, so that I can rotate keys, rename profiles, and remove ones I no longer use.

#### Acceptance Criteria

1. THE UI_Layer SHALL display every persisted Provider_Profile in a list, ordered by `updatedAt` descending, with each entry showing `displayName`, originating preset identifier (or `custom`), masked API key (last 4 characters only), and effective `model`.
2. WHEN the User edits a Provider_Profile, THE UI_Layer SHALL allow modification of `displayName`, `apiKey`, `model`, and `smallFastModel`, and SHALL additionally allow modification of `baseUrl` and `authHeaderStyle` when the Provider_Profile's preset reference is `custom`.
3. WHEN the User saves an edit, THE Provider_Profile_Store SHALL persist the updated fields and SHALL update the Provider_Profile's `updatedAt` timestamp.
4. IF the User edits a preset-derived Provider_Profile and changes `baseUrl`, THEN THE Provider_Profile_Store SHALL reject the change and THE UI_Layer SHALL display an explanation that the `baseUrl` of a preset-derived Provider_Profile is fixed.
5. WHEN the User deletes a Provider_Profile, THE Provider_Profile_Store SHALL remove the Provider_Profile and SHALL overwrite its stored `apiKey` bytes before deletion completes.
6. IF the User deletes the Provider_Profile that is currently the Active_Profile, THEN THE Provider_Profile_Store SHALL clear the Active_Profile reference and THE UI_Layer SHALL route the User to the provider selection screen on the next attempt to start a Session.
7. FOR ALL Provider_Profiles `p` written through the Provider_Profile_Store, reading the Provider_Profile back SHALL yield field values equal to those written, except that the stored form of `apiKey` SHALL be encrypted at rest (write/read round-trip).

### Requirement 5: Active Profile Selection

**User Story:** As a User, I want to choose which Provider_Profile is active, so that my Sessions use the provider I intend.

#### Acceptance Criteria

1. THE Provider_Profile_Store SHALL persist the identifier of at most one Active_Profile.
2. WHEN the User selects a Provider_Profile as active, THE Provider_Profile_Store SHALL set the Active_Profile reference to that Provider_Profile's `profileId` and SHALL notify observers within 200 milliseconds.
3. WHEN an Active_Profile is set, THE UI_Layer SHALL display the Active_Profile's `displayName` and effective `model` in the Sessions list header and in the settings screen.
4. WHEN a new Session is created, THE Bridge SHALL read the Active_Profile at spawn time and SHALL derive environment values from the Active_Profile as specified in Requirement 6.
5. WHILE no Active_Profile is set, THE UI_Layer SHALL block starting a new Session and SHALL display a prompt to select or create a Provider_Profile (supersedes the `no API key → block new Session` behaviour in `android-claude-termux-client` Requirement 6, AC 5).

### Requirement 6: Environment Injection for Claude_CLI Process

**User Story:** As a User, I want the App to launch Claude_CLI with the correct provider environment variables, so that the CLI talks to my selected provider with my key and model.

This requirement supersedes `android-claude-termux-client` Requirement 2, AC 3 for the environment variables related to the Anthropic-compatible endpoint. All other environment variables (`HOME`, `PATH`, `TERM`, `LANG`) continue to be set as specified in the base spec.

#### Acceptance Criteria

1. WHEN the Bridge spawns a Claude_CLI process for a Session, THE Bridge SHALL read the Active_Profile exactly once and SHALL construct the spawn environment from the values read.
2. THE Bridge SHALL set `ANTHROPIC_BASE_URL` in the spawn environment to the Active_Profile's `baseUrl`.
3. WHERE the Active_Profile's `authHeaderStyle` is `apiKey`, THE Bridge SHALL set `ANTHROPIC_API_KEY` in the spawn environment to the Active_Profile's `apiKey` and SHALL NOT set `ANTHROPIC_AUTH_TOKEN`.
4. WHERE the Active_Profile's `authHeaderStyle` is `authToken`, THE Bridge SHALL set `ANTHROPIC_AUTH_TOKEN` in the spawn environment to the Active_Profile's `apiKey` and SHALL NOT set `ANTHROPIC_API_KEY`.
5. THE Bridge SHALL set `ANTHROPIC_MODEL` in the spawn environment to the Active_Profile's `model`.
6. WHERE the Active_Profile declares a non-empty `smallFastModel`, THE Bridge SHALL set `ANTHROPIC_SMALL_FAST_MODEL` in the spawn environment to that value; WHERE the Active_Profile's `smallFastModel` is empty or absent, THE Bridge SHALL NOT set `ANTHROPIC_SMALL_FAST_MODEL` in the spawn environment.
7. THE Bridge SHALL NOT write the Active_Profile's `apiKey` or `baseUrl` to the diagnostics log, to the PTY_Bridge output record, to any persisted file other than the Provider_Profile_Store, or to any UI element other than masked-representation widgets.
8. IF the Active_Profile has been cleared between the Session being opened by the User and the Bridge reading the Active_Profile, THEN THE Bridge SHALL abort the spawn and THE UI_Layer SHALL display a prompt to select or create a Provider_Profile.

### Requirement 7: Connection Test

**User Story:** As a User, I want to test a Provider_Profile before I commit to using it, so that I catch wrong URLs or bad keys up front.

#### Acceptance Criteria

1. THE UI_Layer SHALL expose a Connection_Test action on every Provider_Profile in the list view and on the profile editor form.
2. WHEN the User invokes the Connection_Test for a Provider_Profile, THE App SHALL perform a single lightweight probe that exercises the Provider_Profile's `baseUrl` and `apiKey` and SHALL return a Connection_Test outcome within 15 seconds.
3. WHEN the Connection_Test returns an outcome, THE UI_Layer SHALL display the outcome label and its user-readable reason.
4. IF the Connection_Test outcome is `ok`, THEN THE UI_Layer SHALL display a success indicator next to the Provider_Profile until the Provider_Profile is next edited.
5. THE App SHALL NOT include the Provider_Profile's `apiKey` in the Connection_Test outcome, in the diagnostics log, or in any UI element other than masked-representation widgets.
6. IF the Connection_Test cannot reach `baseUrl` due to no network or DNS failure, THEN THE App SHALL return the outcome `unreachable` together with a reason describing the network condition.
7. IF the Connection_Test reaches `baseUrl` but the provider rejects the credential, THEN THE App SHALL return the outcome `unauthorized`.

### Requirement 8: Migration from the Legacy Single API Key

**User Story:** As an existing User upgrading from a version that stored only a single Anthropic API key, I want my key to be preserved as a Provider_Profile, so that I am not locked out after the upgrade.

#### Acceptance Criteria

1. WHEN the App starts and the Provider_Profile_Store contains no Provider_Profiles and the legacy Credential_Store contains a stored Anthropic API key, THE App SHALL create a new Provider_Profile with `displayName` = `"Anthropic (default)"`, preset reference = `custom`, `baseUrl` = `"https://api.anthropic.com"`, `authHeaderStyle` = `apiKey`, `apiKey` = the legacy stored key, and `model` derived from the legacy `selected Claude model identifier` preference or, if the preference is absent, from a documented fallback value defined in the design phase.
2. WHEN the migration Provider_Profile is created, THE Provider_Profile_Store SHALL set the Active_Profile reference to the new Provider_Profile's `profileId`.
3. WHEN the migration completes successfully, THE App SHALL delete the legacy Anthropic API key entry from the Credential_Store and the legacy `selected Claude model identifier` entry from the Settings_Store.
4. IF the migration fails for any reason, THEN THE App SHALL NOT delete the legacy Credential_Store entry and THE UI_Layer SHALL present a recoverable error with a retry action.
5. WHEN the migration has already been performed, THE App SHALL NOT perform the migration again on subsequent launches.

### Requirement 9: Secure Storage for All Provider Profiles

**User Story:** As a User, I want every provider's API key to be protected with the same safeguards, so that I can trust the App with multiple credentials.

This requirement extends `android-claude-termux-client` Requirement 6 to every Provider_Profile's `apiKey`.

#### Acceptance Criteria

1. THE Provider_Profile_Store SHALL store every Provider_Profile's `apiKey` in EncryptedSharedPreferences backed by a master key stored in the Android Keystore.
2. THE UI_Layer SHALL NOT display any Provider_Profile's `apiKey` in plain text and SHALL display a masked representation revealing at most the last 4 characters.
3. WHEN the User enters an API key in any profile form, THE UI_Layer SHALL clear the input field from its state after the Provider_Profile_Store acknowledges persistence.
4. IF the Android Keystore becomes unavailable such that encrypted values cannot be decrypted, THEN THE Provider_Profile_Store SHALL report a recoverable error to the UI_Layer and THE UI_Layer SHALL prompt the User to re-enter the API key of any affected Provider_Profile.
5. WHEN the User invokes the "remove all credentials" action, THE Provider_Profile_Store SHALL delete every Provider_Profile and SHALL clear the Active_Profile reference.

### Requirement 10: Diagnostics Redaction Across All Profiles

**User Story:** As a User exporting diagnostics, I want every provider API key to be removed from the exported text, so that sharing logs does not leak my credentials.

This requirement extends `android-claude-termux-client` Requirement 13, AC 3 and AC 5.

#### Acceptance Criteria

1. WHEN the User invokes the diagnostics export action, THE App SHALL produce an export text from which every Provider_Profile's `apiKey` has been replaced by a redaction marker.
2. WHERE a Provider_Profile's `baseUrl` contains embedded credentials in its userinfo component (for example `https://user:token@host/`), THE App SHALL redact the userinfo component in the diagnostics export.
3. FOR ALL diagnostics exports `D` produced by the diagnostics export action at time `t` and ALL Provider_Profiles `p` present in the Provider_Profile_Store at time `t`, the string `p.apiKey` SHALL NOT appear as a substring of `D` (redaction property).
4. THE diagnostics log SHALL NOT record any Provider_Profile's `apiKey` at the time the log entry is written, such that redaction is a defence-in-depth check rather than the sole protection.

### Requirement 11: Settings and Onboarding Integration

**User Story:** As a User, I want the provider configuration to be discoverable from the settings and onboarding screens, so that I know where to manage it.

This requirement supersedes `android-claude-termux-client` Requirement 9, AC 1 for the `selected Claude model identifier` preference.

#### Acceptance Criteria

1. THE Settings_Store SHALL NOT persist a standalone `selected Claude model identifier` preference; the effective model SHALL be derived from the Active_Profile's `model` field.
2. THE App SHALL NOT store a standalone selected-model value in any process-lifetime or transient cache that survives across the construction of a Claude_CLI spawn environment; any in-memory selected-model value SHALL be derived from the Active_Profile's `model` field at the time the environment is constructed.
3. THE UI_Layer SHALL expose a "Providers" entry in the settings screen that navigates to the provider list view defined by Requirement 4.
4. WHEN the App is launched for the first time and no Provider_Profile is stored, THE UI_Layer SHALL include a "Configure provider" step in the onboarding flow that navigates to the provider selection screen.
5. WHEN the User attempts to start a new Session while no Active_Profile is set, THE UI_Layer SHALL navigate to the provider selection screen and SHALL display an explanatory message.
6. WHEN the Active_Profile changes, THE UI_Layer SHALL reflect the new Active_Profile's `displayName` and `model` in the settings screen within 200 milliseconds.

### Requirement 12: Provider Profile Correctness Properties

**User Story:** As a developer, I want the provider configuration flow to be covered by explicit correctness properties, so that regressions in credential handling are caught automatically.

#### Acceptance Criteria

1. FOR ALL Provider_Profiles `p` written through the Provider_Profile_Store and subsequently read back as `p'`, the fields `displayName`, presetReference, `baseUrl`, `model`, `smallFastModel`, `authHeaderStyle`, and `apiKey` of `p'` SHALL equal the corresponding fields of `p` (Provider_Profile write/read round-trip property).
2. FOR ALL Provider_Profiles `p` that are the Active_Profile at the time the Bridge spawns a Claude_CLI process, the spawn environment map `E` constructed by the Bridge SHALL satisfy all of the following: `E["ANTHROPIC_BASE_URL"] = p.baseUrl`; exactly one of `E["ANTHROPIC_API_KEY"] = p.apiKey` or `E["ANTHROPIC_AUTH_TOKEN"] = p.apiKey` holds, selected by `p.authHeaderStyle`; `E["ANTHROPIC_MODEL"] = p.model`; and where `p.smallFastModel` is non-empty, `E["ANTHROPIC_SMALL_FAST_MODEL"] = p.smallFastModel` (environment injection property).
3. FOR ALL Provider_Profiles `p` and ALL diagnostics log strings `L` captured during a Claude_CLI Session launched with `p` as the Active_Profile, `p.apiKey` SHALL NOT appear as a substring of `L` (in-memory log non-leakage property).
4. FOR ALL diagnostics exports `D` produced by the diagnostics export action and ALL Provider_Profiles `p` present in the Provider_Profile_Store at export time, `p.apiKey` SHALL NOT appear as a substring of `D` (exported diagnostics non-leakage property).
5. THE Provider_Profile data model and the environment-construction function of the Bridge SHALL be implemented as pure functions over their inputs (no I/O, no global mutable state beyond the supplied stores), so that the properties in this requirement can be exercised by property-based tests.
