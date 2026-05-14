package com.claudemobile.core.bridge.workspace

import android.net.Uri

/**
 * Manages workspace selection, path translation from Android content URIs to
 * proot-accessible filesystem paths, and previously used workspace history.
 *
 * The workspace manager handles:
 * - SAF (Storage Access Framework) URI permission persistence
 * - Path translation from Android URI to proot-accessible filesystem path
 * - Proot mount boundary configuration via [WorkspaceBoundaryValidator]
 * - Fallback to app-managed workspace when external paths are inaccessible
 * - Storage of previously used workspaces for quick selection
 */
public interface WorkspaceManager {

    /**
     * Result of attempting to select a workspace via SAF.
     */
    public sealed interface SelectionResult {
        /**
         * Workspace was successfully selected and URI permission was persisted.
         */
        public data class Success(
            val workspacePath: String,
            val uri: Uri,
            val isPersistable: Boolean,
        ) : SelectionResult

        /**
         * URI permission could not be persisted. The workspace is accessible
         * only for the current process lifetime.
         */
        public data class TemporaryAccess(
            val workspacePath: String,
            val uri: Uri,
            val message: String,
        ) : SelectionResult

        /**
         * The workspace path is not accessible by the proot environment.
         */
        public data class NotAccessibleByProot(
            val workspacePath: String,
            val diagnosticMessage: String,
        ) : SelectionResult

        /**
         * Workspace selection failed entirely.
         */
        public data class Failure(
            val message: String,
            val cause: Throwable? = null,
        ) : SelectionResult
    }

    /**
     * Represents a previously used workspace entry.
     */
    public data class WorkspaceEntry(
        val uri: String,
        val displayName: String,
        val filesystemPath: String,
        val lastUsedTimestamp: Long,
    )

    /**
     * Attempts to persist URI permission for the given SAF directory URI.
     *
     * Calls `takePersistableUriPermission` on the content resolver. If that fails,
     * falls back to temporary access for the current process lifetime.
     *
     * @param uri The SAF directory URI selected by the user.
     * @return A [SelectionResult] indicating the outcome.
     */
    public fun selectWorkspace(uri: Uri): SelectionResult

    /**
     * Translates an Android content URI to a filesystem path accessible by proot.
     *
     * For content:// URIs, this extracts the underlying filesystem path when possible.
     * For file:// URIs, this returns the path directly.
     *
     * @param uri The Android URI to translate.
     * @return The filesystem path accessible by proot, or null if translation is not possible.
     */
    public fun translateUriToProotPath(uri: Uri): String?

    /**
     * Generates proot mount arguments that restrict access to the given workspace path.
     *
     * Delegates to [WorkspaceBoundaryValidator.generateMountArgs].
     *
     * @param workspacePath The workspace directory to expose inside proot.
     * @return List of proot command-line arguments for bind mounting.
     */
    public fun generateMountArgs(workspacePath: String): List<String>

    /**
     * Checks whether the given filesystem path is accessible by the proot environment.
     *
     * A path is considered accessible if it exists on the real filesystem (not just
     * as a content URI) and can be bind-mounted into proot.
     *
     * @param path The filesystem path to check.
     * @return `true` if the path can be used as a proot workspace.
     */
    public fun isPathAccessibleByProot(path: String): Boolean

    /**
     * Returns the app-managed workspace directory path.
     *
     * This is a fallback workspace located in the app's external files directory
     * that is always accessible by proot without additional permissions.
     *
     * @return The absolute path to the app-managed workspace directory.
     */
    public fun getAppManagedWorkspacePath(): String

    /**
     * Returns the list of previously used workspaces, ordered by most recently used first.
     */
    public fun getPreviousWorkspaces(): List<WorkspaceEntry>

    /**
     * Adds or updates a workspace entry in the previously used workspaces list.
     *
     * @param entry The workspace entry to store.
     */
    public fun addWorkspaceToHistory(entry: WorkspaceEntry)

    /**
     * Removes a workspace entry from the previously used workspaces list.
     *
     * @param uri The URI string of the workspace to remove.
     */
    public fun removeWorkspaceFromHistory(uri: String)

    /**
     * Clears all previously used workspace entries.
     */
    public fun clearWorkspaceHistory()

    /**
     * Checks whether the app currently holds a persisted URI permission for the given URI.
     *
     * This is useful for determining whether a previously used workspace will still be
     * accessible on the next app launch.
     *
     * @param uriString The URI string to check.
     * @return `true` if the app holds a persisted read+write permission for this URI.
     */
    public fun hasPersistedPermission(uriString: String): Boolean
}
