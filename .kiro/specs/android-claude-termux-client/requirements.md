# Requirements Document

## Introduction

The Android Claude Mobile Client is a self-contained native Android application that provides a graphical chat-style interface for interacting with Claude Code CLI running inside an embedded Linux environment. The application bundles its own terminal emulator (using Termux's `terminal-emulator` JNI library from JitPack), a minimal Linux prefix filesystem, and a proot-based Ubuntu rootfs — all within a single APK. Users install one application and everything works without any external dependencies.

The application is built using modern Android architecture best practices (Jetpack Compose for UI, Kotlin Coroutines and Flow for concurrency, Hilt for dependency injection, Room for persistence, DataStore for preferences, Android Keystore for secrets, and a modular clean-architecture layering of presentation, domain, and data).

The application is responsible for:

1. Bundling and bootstrapping an embedded Linux prefix filesystem and proot Ubuntu rootfs within the app's private storage, including installing the Claude CLI and its runtime dependencies (Node.js).
2. Spawning and supervising the Claude CLI as a long-running child process via the embedded terminal-emulator library's JNI PTY interface, forwarding user input and streaming output back to the UI.
3. Presenting a chat-style conversation UI with streaming rendering of markdown, code blocks, and tool/diff outputs.
4. Persisting conversation history, sessions, and user settings locally.
5. Securely storing API credentials using the Android Keystore.
6. Providing workspace/file access so that Claude Code can read and write project files under user control.

Out of scope: building a cloud backend, implementing Claude's model locally, bundling Anthropic-proprietary binaries, and requiring any external application (including Termux) to be installed.

## Glossary

- **App**: The Android application being specified in this document; a single self-contained APK.
- **UI_Layer**: The Jetpack Compose presentation layer of the App, including Activities, Composables, and ViewModels.
- **Domain_Layer**: The Kotlin module containing use cases, domain models, and repository interfaces, independent of Android framework types.
- **Data_Layer**: The Kotlin module containing repository implementations, Room database access, DataStore access, and bridge clients.
- **Bridge**: The component responsible for bidirectional communication between the App and the Claude CLI process running inside the embedded proot environment.
- **PTY_Bridge**: A specialization of the Bridge that communicates with the child process via a pseudo-terminal file descriptor obtained from the Terminal_Emulator_Lib.
- **Terminal_Emulator_Lib**: The Termux `terminal-emulator` library (from JitPack `com.termux.termux-app:terminal-emulator`) providing JNI-based PTY allocation, fork, and exec capabilities.
- **Terminal_View_Lib**: The optional Termux `terminal-view` library (from JitPack `com.termux.termux-app:terminal-view`) used for debug terminal rendering if needed.
- **Termux_Shared_Lib**: The Termux `termux-shared` library (from JitPack `com.termux.termux-app:termux-shared`) providing shell and filesystem utilities.
- **Embedded_Prefix**: The minimal Termux-compatible Linux prefix filesystem (containing bin, lib, etc, usr directories with essential packages) bundled as app assets or downloaded on first launch, stored in the App's private storage.
- **Proot_Env**: The Ubuntu rootfs installed and managed by proot inside the Embedded_Prefix, stored in the App's private storage.
- **Proot_Binary**: The proot executable installed within the Embedded_Prefix, used to create a chroot-like environment without root access.
- **Claude_CLI**: The Claude Code command-line tool installed inside Proot_Env and invoked by the App.
- **Session**: A single logical conversation with Claude_CLI, consisting of an ordered list of Messages and an associated Workspace.
- **Message**: A single turn in a Session, attributed to either the User, Claude_CLI, or the System, with a role, timestamp, content, and optional tool-call metadata.
- **Workspace**: A directory on the device filesystem exposed to Claude_CLI as its working directory via proot bind mounts.
- **Credential_Store**: The component that stores API keys and tokens, backed by the Android Keystore via EncryptedSharedPreferences or Jetpack Security.
- **Foreground_Service**: An Android foreground service used to keep the Claude_CLI process alive while the App is backgrounded.
- **Bootstrap_Manager**: The component that orchestrates extraction of the Embedded_Prefix, installation of Proot_Env, Node.js, and Claude_CLI within the App's private storage.
- **Settings_Store**: The DataStore-backed component that persists user preferences.
- **Conversation_Repository**: The repository responsible for persisting and retrieving Sessions and Messages from the Room database.
- **Pretty_Printer**: The component that renders Claude_CLI streamed output (including ANSI escape sequences, markdown, and tool-call JSON) into structured UI models.
- **Output_Parser**: The component that parses raw byte streams from the PTY_Bridge into structured events (text chunks, tool calls, command prompts, completions, errors).
- **User**: The human operator of the device using the App.

## Requirements

### Requirement 1: Embedded Environment Bootstrap

**User Story:** As a User, I want the App to set up its own embedded Linux environment on first launch, so that I can use Claude Code without installing any other application.

#### Acceptance Criteria

1. WHEN the App is launched for the first time, THE Bootstrap_Manager SHALL extract the Embedded_Prefix from bundled assets or download it from a configured URL into the App's private storage directory.
2. WHEN the Embedded_Prefix is available, THE Bootstrap_Manager SHALL verify that the Proot_Binary is present and executable within the Embedded_Prefix and SHALL set the correct file permissions (chmod 755) on all binaries.
3. WHEN the Proot_Binary is available, THE Bootstrap_Manager SHALL verify that Proot_Env (Ubuntu rootfs) is installed in the App's private storage and SHALL download and extract it if absent.
4. WHEN Proot_Env is installed, THE Bootstrap_Manager SHALL verify that Node.js of the version required by Claude_CLI and Claude_CLI itself are installed inside Proot_Env and SHALL install them if they are absent.
5. WHILE any bootstrap step is executing, THE UI_Layer SHALL display the current step name, a progress indicator, download percentage where applicable, and the most recent line of installer output.
6. IF any bootstrap step fails due to insufficient storage space, THEN THE Bootstrap_Manager SHALL report the required and available space to the UI_Layer and SHALL offer guidance to free space.
7. IF any bootstrap step fails with a non-zero exit code or extraction error, THEN THE Bootstrap_Manager SHALL record the failure cause, SHALL surface a user-readable error to the UI_Layer, and SHALL offer a retry action.
8. THE Bootstrap_Manager SHALL expose a health-check operation that reports the installed status and version of the Embedded_Prefix, Proot_Binary, Proot_Env, Node.js, and Claude_CLI.
9. WHEN the User opens the settings screen, THE UI_Layer SHALL display the most recent health-check result including storage usage and SHALL provide a control to re-run the health check.
10. THE Bootstrap_Manager SHALL store a version marker for the Embedded_Prefix and SHALL support upgrading the prefix when the App is updated with a newer bundled version.
11. WHEN the App detects that the Embedded_Prefix version marker is older than the bundled version, THE Bootstrap_Manager SHALL perform an incremental update or full re-extraction as appropriate and SHALL preserve Proot_Env and user data.

### Requirement 2: Launching the Claude CLI Process via Embedded Terminal

**User Story:** As a User, I want the App to start Claude_CLI inside the embedded proot environment using the bundled terminal emulator library, so that I can interact with Claude through the App UI without any external dependencies.

#### Acceptance Criteria

1. WHEN the User opens a Session, THE Bridge SHALL use the Terminal_Emulator_Lib JNI interface to allocate a new PTY and fork/exec a shell process that invokes proot with the Proot_Env rootfs and the Session's Workspace as a bind-mounted working directory.
2. THE Bridge SHALL configure the proot invocation with appropriate bind mounts: the Workspace directory, `/dev`, `/proc`, and `/sys` as required by the Ubuntu rootfs.
3. THE Bridge SHALL set the environment variables `HOME`, `PATH`, `TERM`, `LANG`, and `ANTHROPIC_API_KEY` before exec within the forked process.
4. WHEN the proot shell is ready, THE Bridge SHALL execute the Claude_CLI command within the proot environment.
5. THE Bridge SHALL connect the PTY file descriptor from the Terminal_Emulator_Lib to the PTY_Bridge for bidirectional byte streaming.
6. WHEN the Claude_CLI process is spawned, THE Bridge SHALL record the process identifier and the start timestamp in the Session's runtime state.
7. IF spawning the process fails (fork failure, exec failure, or proot startup error), THEN THE Bridge SHALL report the failure to the UI_Layer with the command line, error code, and last 4096 bytes of combined output.
8. WHILE the Claude_CLI process is running, THE Bridge SHALL forward bytes written by the UI_Layer to the PTY within 100 milliseconds of receipt.
9. WHILE the Claude_CLI process is running, THE Bridge SHALL emit output chunks read from the PTY to the Output_Parser within 100 milliseconds of availability.
10. WHEN the Claude_CLI process exits, THE Bridge SHALL emit a termination event containing the exit code and a cause classification of `normal`, `user_cancelled`, `killed_by_os`, or `crash`.
11. WHEN the User requests cancellation of an in-flight Claude_CLI turn, THE Bridge SHALL send SIGINT to the process group within 200 milliseconds.
12. IF the Claude_CLI process does not terminate within 5 seconds of SIGINT, THEN THE Bridge SHALL escalate to SIGTERM, and IF it does not terminate within a further 5 seconds, THEN THE Bridge SHALL escalate to SIGKILL.
13. THE Bridge SHALL ensure that no more than one Claude_CLI process per Session is active at any time.

### Requirement 3: Bidirectional Streaming UI

**User Story:** As a User, I want to see Claude's responses stream into the conversation as they are generated, so that I get immediate feedback and can interrupt long answers.

#### Acceptance Criteria

1. WHEN the Output_Parser emits a text chunk for the current Session, THE UI_Layer SHALL append the chunk to the active assistant Message within 100 milliseconds.
2. WHILE an assistant Message is streaming, THE UI_Layer SHALL display a visible streaming indicator on that Message.
3. WHEN the Output_Parser emits a tool-call event, THE UI_Layer SHALL render the tool name, arguments, and status (pending, running, completed, or failed) as a structured block inside the active assistant Message, including when the Message has been marked as cancelled.
4. WHEN the Output_Parser emits a completion event for a turn, THE UI_Layer SHALL mark the active assistant Message as complete and SHALL remove the streaming indicator.
5. WHEN the User taps the send control with non-empty input, THE UI_Layer SHALL append a user Message to the Session, SHALL forward the input to the Bridge, and SHALL clear the input field.
6. WHILE an assistant turn is in progress, THE UI_Layer SHALL display a cancel control in place of the send control, except during the transition between user and assistant turns where both controls MAY be visible briefly for up to 300 milliseconds.
7. WHEN the User taps the cancel control, THE UI_Layer SHALL invoke cancellation on the Bridge and SHALL mark the active assistant Message as cancelled once the Bridge emits a cancellation confirmation.
8. IF the Bridge emits an error event during streaming, THEN THE UI_Layer SHALL append a system Message describing the error and SHALL leave the conversation in a state that permits the next user turn.

### Requirement 4: Output Parsing and Rendering

**User Story:** As a User, I want Claude's output to render with proper markdown, code highlighting, and structured tool-call displays, so that I can read and copy results easily.

#### Acceptance Criteria

1. THE Output_Parser SHALL consume a byte stream from the PTY_Bridge and SHALL produce a sequence of structured events of type `text`, `tool_call_start`, `tool_call_result`, `prompt`, `turn_complete`, or `error`.
2. THE Output_Parser SHALL strip ANSI escape sequences from text events while preserving their semantic effect (for example emitting a separate `style_hint` metadata field) when the upstream tool uses ANSI colors for emphasis.
3. THE Pretty_Printer SHALL render text events as markdown, including headings, lists, bold, italic, inline code, code fences, tables, and links.
4. WHEN a code fence specifies a language, THE Pretty_Printer SHALL attempt syntax highlighting for that language, and IF the language is not supported by the highlighter, THEN THE Pretty_Printer SHALL fall back to plain monospace rendering.
5. THE Pretty_Printer SHALL render tool-call blocks with a distinct visual style that shows the tool name, collapsible arguments, and collapsible results.
6. THE Pretty_Printer SHALL provide a copy action on every Message and on every code block that copies the underlying raw text (without markdown syntax coloring) to the system clipboard.
7. FOR ALL byte sequences `b` produced by the PTY_Bridge for a single turn, concatenating the `text` event payloads in order and stripping only the ANSI escapes SHALL yield the same string as stripping ANSI escapes from `b` directly (stream reassembly round-trip).
8. FOR ALL Message contents `m` composed only of standard markdown that the Pretty_Printer supports, rendering `m` and then extracting the plain text via the copy action SHALL yield a string equal to `m` with markdown syntax removed but semantic content preserved (markdown round-trip for copyable content).

### Requirement 5: Session and Conversation Persistence

**User Story:** As a User, I want my conversations to be saved and resumable across app restarts, so that I can continue work without losing context.

#### Acceptance Criteria

1. THE Conversation_Repository SHALL persist every Session with a unique identifier, a title, a Workspace path, a creation timestamp, and a last-activity timestamp.
2. THE Conversation_Repository SHALL persist every Message with a Session identifier, a role of `user`, `assistant`, `tool`, or `system`, a creation timestamp, an ordered position, and a content payload.
3. WHEN a new user Message is submitted, THE Conversation_Repository SHALL persist it before it is forwarded to the Bridge.
4. WHEN the Output_Parser emits a `turn_complete` event, THE Conversation_Repository SHALL persist the final assistant Message content.
5. WHILE an assistant Message is streaming, THE Conversation_Repository SHALL persist incremental content at least once every 2 seconds so that no more than 2 seconds of streamed content is lost on process death.
6. WHEN the User opens the Sessions list, THE UI_Layer SHALL display Sessions ordered by last-activity timestamp descending, with each entry showing the title, last-activity timestamp, and Message count; WHEN the Session list is empty, THE UI_Layer SHALL render an empty-state placeholder; IF the Conversation_Repository fails to load Sessions, THEN THE UI_Layer SHALL render a recoverable error state with a retry action.
7. WHEN the User selects a Session, THE UI_Layer SHALL load and display all Messages in the Session in ascending position order.
8. WHEN the User deletes a Session, THE Conversation_Repository SHALL remove the Session and all associated Messages in a single transaction.
9. WHEN the User renames a Session, THE Conversation_Repository SHALL update the Session's title and last-activity timestamp.
10. FOR ALL Sessions `s` and Messages `m` written through the Conversation_Repository, reading `s` back and reading its Messages SHALL yield the same values that were written (write-read round-trip).

### Requirement 6: Credential Storage and API Key Management

**User Story:** As a User, I want my Anthropic API key and any other Claude_CLI credentials to be stored securely on the device, so that they are protected from other apps and casual inspection.

#### Acceptance Criteria

1. THE Credential_Store SHALL store the Anthropic API key and any OAuth tokens using EncryptedSharedPreferences backed by a master key stored in the Android Keystore.
2. WHEN the User enters an API key in the settings screen, THE Credential_Store SHALL persist the key and SHALL clear the input field from the UI_Layer.
3. THE UI_Layer SHALL never display a stored API key in plain text; instead THE UI_Layer SHALL display a masked representation that reveals at most the last 4 characters.
4. WHEN the Bridge spawns a Claude_CLI process, THE Bridge SHALL pass the stored API key to the process via the `ANTHROPIC_API_KEY` environment variable set before exec and SHALL not write the key to any log, file, or UI element.
5. IF no API key is stored, THEN THE UI_Layer SHALL block starting a new Session and SHALL prompt the User to configure a key.
6. WHEN the User requests removal of the stored credentials, THE Credential_Store SHALL delete all stored keys and tokens and SHALL confirm deletion to the UI_Layer.
7. IF the underlying Android Keystore becomes unavailable (for example after a device unlock change that invalidates keys), THEN THE Credential_Store SHALL report a recoverable error and SHALL prompt the User to re-enter the credentials.

### Requirement 7: Foreground Service for Long-Running CLI Session

**User Story:** As a User, I want Claude_CLI to keep running when the App is in the background, so that long answers and tool calls complete even if I switch apps.

#### Acceptance Criteria

1. WHEN a Claude_CLI process is started for a Session, THE App SHALL start a Foreground_Service with a persistent notification that displays the Session title and current turn status.
2. WHILE the Foreground_Service is running, THE App SHALL hold the Bridge and PTY_Bridge in memory so that streaming continues while the UI is backgrounded.
3. WHEN all active Claude_CLI processes have terminated, THE App SHALL stop the Foreground_Service and SHALL dismiss its notification.
4. WHEN the User taps the Foreground_Service notification, THE App SHALL open the associated Session in the UI_Layer.
5. THE Foreground_Service SHALL use the `dataSync` or equivalent foreground service type appropriate for the target Android API level and SHALL declare the corresponding `FOREGROUND_SERVICE_*` permission in the manifest.
6. IF the operating system kills the Foreground_Service, THEN on the next App launch THE Bridge SHALL detect the missing process and SHALL mark any in-flight assistant Messages as `killed_by_os` with a system note.

### Requirement 8: Workspace and File Access

**User Story:** As a User, I want Claude_CLI to be able to read and write files in a project directory that I control, so that Claude Code can edit code in my projects.

#### Acceptance Criteria

1. WHEN the User creates a new Session, THE UI_Layer SHALL prompt the User to select a Workspace from a list of previously used Workspaces or to create a new one inside the App-scoped storage or a user-granted directory.
2. WHEN the User selects a directory via the Android Storage Access Framework, THE App SHALL attempt to persist the URI permission using `takePersistableUriPermission` so that the directory remains accessible across restarts; IF `takePersistableUriPermission` fails, THEN THE App SHALL proceed with temporary URI access for the current process lifetime and SHALL inform the User that the Workspace will become inaccessible on the next App launch.
3. THE Bridge SHALL configure the proot invocation to bind-mount the selected Workspace directory into the Proot_Env at a predictable path (for example `/workspace`) and SHALL set the Claude_CLI working directory to that mount point.
4. IF the selected Workspace path is not accessible by the App process, THEN THE UI_Layer SHALL display a diagnostic message explaining the access issue and SHALL offer to use an App-managed Workspace instead.
5. WHEN Claude_CLI writes to a file inside the Workspace, THE App SHALL not require any additional user confirmation beyond the initial directory grant.
6. WHEN Claude_CLI attempts to access a path outside the Workspace, THE Bridge SHALL restrict access at the proot bind-mount boundary configured during process launch.
7. THE UI_Layer SHALL display the Workspace path in the Session header and SHALL provide a control to open a file-tree view of that Workspace.

### Requirement 9: Settings and Preferences

**User Story:** As a User, I want to configure models, prompts, and UI preferences, so that the App behaves how I expect.

#### Acceptance Criteria

1. THE Settings_Store SHALL persist the following preferences: selected Claude model identifier, default system prompt, theme mode (`system`, `light`, `dark`), font scale, streaming render rate, workspace default path, and enable/disable flag for auto-starting the Foreground_Service.
2. WHEN the User changes any preference, THE Settings_Store SHALL persist the new value and SHALL notify observers within 200 milliseconds.
3. THE UI_Layer SHALL expose all persisted preferences through a settings screen and SHALL validate numeric inputs (for example font scale and streaming render rate) against declared ranges before persisting.
4. IF a persisted preference value falls outside its declared valid range after an app upgrade, THEN THE Settings_Store SHALL fall back to the declared default value and SHALL log a migration note.
5. WHEN the User toggles the theme mode, THE UI_Layer SHALL update the active theme within one recomposition cycle.
6. FOR ALL preference keys `k` and valid values `v`, writing `v` to `k` through the Settings_Store and reading `k` back SHALL return `v` (settings round-trip).

### Requirement 10: Permissions and Manifest Declarations

**User Story:** As a User, I want the App to request only the permissions it actually needs, so that I can trust it with my device.

#### Acceptance Criteria

1. THE App SHALL declare in its manifest only the following permissions: `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, and the appropriate specialized `FOREGROUND_SERVICE_*` permission for the service type.
2. WHEN the App targets Android API level 33 or higher and posts its first notification, THE UI_Layer SHALL request the `POST_NOTIFICATIONS` permission at runtime if it is not already granted.
3. IF the User denies the `POST_NOTIFICATIONS` permission, THEN THE App SHALL continue to operate but SHALL warn the User that the Foreground_Service notification may be suppressed.
4. THE App SHALL not request the legacy `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE` permissions; file access SHALL be performed exclusively through Storage Access Framework URI grants or app-scoped storage.
5. THE App SHALL not request any inter-app communication permissions; all Linux environment operations SHALL be performed within the App's own process space using the Terminal_Emulator_Lib JNI interface.

### Requirement 11: Network and Offline Behavior

**User Story:** As a User, I want the App to behave predictably when the device is offline or has a poor connection, so that I am not left guessing about failures.

#### Acceptance Criteria

1. WHEN the User submits a user Message while the device has no active network, THE UI_Layer SHALL display an offline banner and SHALL forward the Message to the Bridge nonetheless so that Claude_CLI can report its own network error.
2. IF the Claude_CLI process emits a network-error signature recognised by the Output_Parser, THEN THE UI_Layer SHALL render the error as a structured system Message with a retry action.
3. WHEN the User taps the retry action on a failed turn, THE UI_Layer SHALL resend the same user Message to the Bridge without duplicating the user Message in the conversation history.
4. WHILE the network is unavailable, THE UI_Layer SHALL continue to allow browsing, searching, copying, and deleting existing Sessions.
5. THE App SHALL not perform any outbound network request directly except for bootstrap asset downloads; all network traffic to Anthropic services SHALL originate from the Claude_CLI process inside Proot_Env.

### Requirement 12: Modern Android Architecture Conformance

**User Story:** As a developer maintaining the App, I want the codebase to follow current Android architecture best practices, so that it is testable, modular, and evolvable.

#### Acceptance Criteria

1. THE App SHALL be structured as a multi-module Gradle project with at least the modules `app`, `feature-chat`, `feature-settings`, `feature-sessions`, `core-domain`, `core-data`, `core-bridge`, `core-ui`, and `core-common`.
2. THE Domain_Layer SHALL not depend on any Android framework types and SHALL expose use cases as suspending functions or Flows.
3. THE UI_Layer SHALL be implemented with Jetpack Compose and SHALL use ViewModels that expose `StateFlow<UiState>` and accept `Intent` or `Action` values, following a unidirectional data flow.
4. THE Data_Layer SHALL implement each Domain_Layer repository interface exactly once and SHALL be the only layer permitted to import Room, DataStore, and Android Keystore APIs.
5. THE App SHALL use Hilt for dependency injection, with one `@HiltAndroidApp` application class and module definitions collocated with their providing layer.
6. THE App SHALL use Kotlin Coroutines and Flow for all asynchronous work and SHALL not use `AsyncTask`, `Thread`, or `Handler` for business logic.
7. THE App SHALL configure a minimum SDK version of 26 or higher and a target SDK version of at least the configured minimum SDK version; the default build configuration SHALL set the target SDK to the latest stable Android release at the time of the build.
8. THE App SHALL enable Compose's strong-skipping mode, Kotlin's explicit-api mode for core modules, and lint checks for the `UnusedResources`, `MissingPermission`, and `ComposeUnstableCollections` rules.
9. THE App SHALL include unit tests for every use case in the Domain_Layer and for every repository in the Data_Layer, with a minimum line coverage threshold enforced in CI.

### Requirement 13: Error Handling and Diagnostics

**User Story:** As a User, I want clear diagnostics when something goes wrong, so that I can fix configuration issues without guessing.

#### Acceptance Criteria

1. THE App SHALL maintain an in-app diagnostics log that records bootstrap events, Bridge lifecycle events, and the last 256 lines of Claude_CLI stderr per Session.
2. IF the Bridge detects that the Claude_CLI process has exited with a non-zero code, THEN THE UI_Layer SHALL display the exit code and a link to the diagnostics log.
3. WHEN the User taps the diagnostics log entry, THE UI_Layer SHALL display the recorded log with a share action that exports it as a text file redacted of any value matching the stored API key.
4. IF a crash occurs in the UI_Layer or Data_Layer, THEN THE App SHALL record the stack trace in the diagnostics log and SHALL not transmit it off the device unless the User explicitly opts in to crash reporting.
5. FOR ALL diagnostics entries exported via the share action, the exported text SHALL not contain the stored API key (redaction property).

### Requirement 14: Output Parser Correctness (Parser and Pretty-Printer Round-Trip)

**User Story:** As a developer, I want the Output_Parser and Pretty_Printer to be provably consistent, so that streamed output is never silently corrupted.

#### Acceptance Criteria

1. THE Output_Parser SHALL define a grammar for its event stream covering text chunks, tool-call frames delimited by recognisable sentinels emitted by Claude_CLI, prompt lines, and turn-completion markers.
2. THE Pretty_Printer SHALL be able to serialize any sequence of Output_Parser events back into a byte stream that, when re-parsed by the Output_Parser, produces an equivalent sequence (parser/printer round-trip).
3. FOR ALL byte streams `b` that the Output_Parser accepts without emitting an `error` event, parsing `b` to events `e`, printing `e` via the Pretty_Printer to `b'`, and parsing `b'` SHALL yield events equivalent to `e` under the defined event equivalence relation.
4. IF the Output_Parser encounters a byte sequence that does not conform to the grammar, THEN THE Output_Parser SHALL emit a single `error` event with a descriptive reason and SHALL resynchronise on the next recognisable frame boundary.
5. THE Output_Parser SHALL be implemented as a pure function over its input buffer (no I/O, no global state beyond the accumulating buffer), so that the round-trip property can be exercised by property-based tests.

### Requirement 15: Accessibility

**User Story:** As a User who relies on accessibility features, I want the App's chat UI to be fully usable with TalkBack and large fonts, so that I can use Claude regardless of ability.

#### Acceptance Criteria

1. THE UI_Layer SHALL provide a non-empty content description for every interactive Composable, including send, cancel, copy, and retry controls.
2. WHEN the system font scale is increased, THE UI_Layer SHALL reflow chat Messages without horizontal clipping for font scales up to 2.0.
3. THE UI_Layer SHALL ensure that all text and its background meet a contrast ratio of at least 4.5:1 in both the light and dark themes for standard text and at least 3:1 for large text.
4. THE UI_Layer SHALL support keyboard navigation such that every interactive control can be reached and activated using an external keyboard.
5. WHEN TalkBack is enabled, THE UI_Layer SHALL announce streaming assistant Message updates no more than once every 2 seconds to avoid overwhelming the User; WHILE TalkBack is disabled, THE UI_Layer SHALL not apply this announcement rate limit.
