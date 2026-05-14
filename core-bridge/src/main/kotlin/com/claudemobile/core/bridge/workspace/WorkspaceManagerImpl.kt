package com.claudemobile.core.bridge.workspace

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [WorkspaceManager] that handles workspace selection via SAF,
 * path translation from Android URIs to proot-accessible filesystem paths,
 * and previously used workspace history.
 *
 * Key behaviors:
 * - Attempts `takePersistableUriPermission` for SAF URIs; falls back to temporary access on failure
 * - Translates content:// URIs to filesystem paths by extracting the document path
 * - Uses [WorkspaceBoundaryValidator] for mount boundary configuration
 * - Provides app-managed workspace via [Context.getExternalFilesDir] as fallback
 * - Stores previously used workspaces in [WorkspacePreferencesStore]
 * - Releases persisted URI permissions when workspaces are removed from history
 *
 * Requirements satisfied:
 * - Req 8.1: Provides previously used workspaces list and app-managed workspace creation
 * - Req 8.2: Persists SAF URI permissions; falls back to temporary access on failure
 * - Req 8.4: Reports inaccessible paths with diagnostic message and fallback suggestion
 * - Req 8.5: No additional confirmation needed after initial directory grant
 * - Req 8.6: Restricts access at proot bind-mount boundary via WorkspaceBoundaryValidator
 */
@Singleton
public class WorkspaceManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val boundaryValidator: WorkspaceBoundaryValidator,
    private val preferencesStore: WorkspacePreferencesStore,
) : WorkspaceManager {

    override fun selectWorkspace(uri: Uri): WorkspaceManager.SelectionResult {
        // Attempt to persist URI permission
        val isPersistable = tryTakePersistablePermission(uri)

        // Translate URI to filesystem path
        val filesystemPath = translateUriToProotPath(uri)
            ?: return WorkspaceManager.SelectionResult.Failure(
                message = "Unable to translate URI to filesystem path: $uri"
            )

        // Check if the path is accessible by proot
        if (!isPathAccessibleByProot(filesystemPath)) {
            return WorkspaceManager.SelectionResult.NotAccessibleByProot(
                workspacePath = filesystemPath,
                diagnosticMessage = buildProotDiagnosticMessage(filesystemPath),
            )
        }

        // Validate the workspace path (exists, is directory, is readable)
        // Only reject if the path exists but is not a directory or not readable.
        // Non-existent paths are acceptable — the directory may be created later.
        val validation = boundaryValidator.validateWorkspacePath(filesystemPath)
        if (validation is WorkspaceBoundaryValidator.ValidationResult.Invalid) {
            val file = File(filesystemPath)
            // Only fail if the path exists but is invalid (not a directory, not readable)
            // If it doesn't exist and can't be created, that's also a failure
            if (file.exists() || !filesystemPath.startsWith("/storage")) {
                return WorkspaceManager.SelectionResult.NotAccessibleByProot(
                    workspacePath = filesystemPath,
                    diagnosticMessage = validation.reason +
                        " You can use the app-managed workspace at " +
                        "'${getAppManagedWorkspacePath()}' instead.",
                )
            }
            // Path doesn't exist but is under /storage — allow it (user may create it)
        }

        // Add to workspace history
        val displayName = extractDisplayName(uri, filesystemPath)
        addWorkspaceToHistory(
            WorkspaceManager.WorkspaceEntry(
                uri = uri.toString(),
                displayName = displayName,
                filesystemPath = filesystemPath,
                lastUsedTimestamp = System.currentTimeMillis(),
            )
        )

        return if (isPersistable) {
            WorkspaceManager.SelectionResult.Success(
                workspacePath = filesystemPath,
                uri = uri,
                isPersistable = true,
            )
        } else {
            WorkspaceManager.SelectionResult.TemporaryAccess(
                workspacePath = filesystemPath,
                uri = uri,
                message = "URI permission could not be persisted. " +
                    "This workspace will become inaccessible on the next app launch. " +
                    "Consider using the app-managed workspace for reliable access.",
            )
        }
    }

    override fun translateUriToProotPath(uri: Uri): String? {
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> translateContentUriToPath(uri)
            else -> null
        }
    }

    override fun generateMountArgs(workspacePath: String): List<String> {
        return boundaryValidator.generateMountArgs(workspacePath)
    }

    override fun isPathAccessibleByProot(path: String): Boolean {
        if (path.isBlank()) return false

        val file = File(path)

        // The path must be an absolute path on the real filesystem
        if (!file.isAbsolute) return false

        // Check that the path is under a location that proot can bind-mount.
        // Proot can access paths under:
        // - /storage/emulated/0 (external storage)
        // - /data/data/<package>/files (app internal files)
        // - /data/user/0/<package>/files (app internal files, alternate path)
        // - App's external files directory
        val accessiblePrefixes = buildAccessiblePrefixes()
        val canonicalPath = try {
            file.canonicalPath
        } catch (_: Exception) {
            path
        }

        return accessiblePrefixes.any { prefix ->
            canonicalPath.startsWith(prefix)
        }
    }

    override fun getAppManagedWorkspacePath(): String {
        val workspaceDir = File(
            context.getExternalFilesDir(null),
            APP_MANAGED_WORKSPACE_DIR
        )
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
        }
        return workspaceDir.absolutePath
    }

    override fun getPreviousWorkspaces(): List<WorkspaceManager.WorkspaceEntry> {
        val json = preferencesStore.getWorkspaceEntries() ?: return emptyList()
        return try {
            parseWorkspaceEntries(json).sortedByDescending { it.lastUsedTimestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun addWorkspaceToHistory(entry: WorkspaceManager.WorkspaceEntry) {
        val existing = getPreviousWorkspaces().toMutableList()

        // Remove existing entry with same URI (will be re-added with updated timestamp)
        existing.removeAll { it.uri == entry.uri }

        // Add new entry at the beginning
        existing.add(0, entry)

        // Limit history size
        val trimmed = existing.take(MAX_WORKSPACE_HISTORY)

        preferencesStore.setWorkspaceEntries(serializeWorkspaceEntries(trimmed))
    }

    override fun removeWorkspaceFromHistory(uri: String) {
        // Release persisted URI permission if held
        releasePersistablePermission(uri)

        val existing = getPreviousWorkspaces().toMutableList()
        existing.removeAll { it.uri == uri }
        preferencesStore.setWorkspaceEntries(serializeWorkspaceEntries(existing))
    }

    override fun clearWorkspaceHistory() {
        // Release all persisted URI permissions for workspace entries
        val entries = getPreviousWorkspaces()
        for (entry in entries) {
            releasePersistablePermission(entry.uri)
        }
        preferencesStore.clearWorkspaceEntries()
    }

    /**
     * Returns the list of currently persisted URI permissions held by the app.
     * Useful for diagnostics and verifying permission state.
     */
    public fun getPersistedUriPermissions(): List<UriPermission> {
        return context.contentResolver.persistedUriPermissions
    }

    /**
     * Checks whether the app currently holds a persisted URI permission for the given URI.
     *
     * @param uriString The URI string to check.
     * @return `true` if the app holds a persisted read+write permission for this URI.
     */
    override public fun hasPersistedPermission(uriString: String): Boolean {
        val targetUri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return false
        }
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == targetUri &&
                permission.isReadPermission &&
                permission.isWritePermission
        }
    }

    // --- Internal Helpers ---

    /**
     * Attempts to take persistable URI permission for the given URI.
     * Returns true if successful, false otherwise.
     */
    internal fun tryTakePersistablePermission(uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Releases a previously persisted URI permission.
     * Silently ignores errors (e.g., if the permission was already released).
     */
    internal fun releasePersistablePermission(uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Permission was not held or already released
        } catch (_: Exception) {
            // Ignore other errors during cleanup
        }
    }

    /**
     * Translates a content:// URI to a filesystem path.
     *
     * Handles the common case of DocumentsProvider URIs from the system file picker,
     * which encode the filesystem path in the document ID.
     */
    internal fun translateContentUriToPath(uri: Uri): String? {
        // Handle primary external storage documents
        if (isExternalStorageDocument(uri)) {
            val docId = getDocumentId(uri) ?: return null
            val parts = docId.split(":")
            if (parts.size >= 2) {
                val type = parts[0]
                val relativePath = parts.subList(1, parts.size).joinToString(":")
                if ("primary".equals(type, ignoreCase = true)) {
                    return "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
                }
                // For non-primary volumes, try /storage/<volume>/<path>
                return "/storage/$type/$relativePath"
            }
            // If docId is just "primary" with no path, return external storage root
            if ("primary".equals(docId, ignoreCase = true)) {
                return Environment.getExternalStorageDirectory().absolutePath
            }
        }

        // Handle downloads documents
        if (isDownloadsDocument(uri)) {
            val docId = getDocumentId(uri) ?: return null
            // Raw path format: "raw:/storage/emulated/0/Download/..."
            if (docId.startsWith("raw:")) {
                return docId.removePrefix("raw:")
            }
        }

        // Try to extract path from the URI path segments directly
        // Some providers encode the path in the URI itself
        val path = uri.path
        if (path != null) {
            // Pattern: /tree/primary:path/to/dir or /document/primary:path/to/dir
            val treeMatch = extractPathFromTreeUri(path)
            if (treeMatch != null) return treeMatch
        }

        return null
    }

    /**
     * Extracts the filesystem path from a tree URI path segment.
     * Handles patterns like /tree/primary:path/to/dir
     */
    internal fun extractPathFromTreeUri(uriPath: String): String? {
        // Match patterns like /tree/primary:some/path or /document/primary:some/path
        val patterns = listOf("/tree/primary:", "/document/primary:")
        for (pattern in patterns) {
            val index = uriPath.indexOf(pattern)
            if (index >= 0) {
                val relativePath = uriPath.substring(index + pattern.length)
                return "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
            }
        }

        // Match non-primary volumes: /tree/<volume>:path
        val volumePattern = Regex("/(?:tree|document)/([^:]+):(.*)")
        val match = volumePattern.find(uriPath)
        if (match != null) {
            val volume = match.groupValues[1]
            val relativePath = match.groupValues[2]
            if (volume != "primary") {
                return "/storage/$volume/$relativePath"
            }
        }

        return null
    }

    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    private fun getDocumentId(uri: Uri): String? {
        return try {
            DocumentsContract.getTreeDocumentId(uri)
                ?: DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractDisplayName(uri: Uri, fallbackPath: String): String {
        // Try to get display name from content resolver
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val name = it.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
            // Fall through to path-based name
        }

        // Fall back to last path segment
        return File(fallbackPath).name.ifBlank { fallbackPath }
    }

    private fun buildAccessiblePrefixes(): List<String> {
        val prefixes = mutableListOf<String>()

        // External storage (most common workspace location)
        val externalStorage = Environment.getExternalStorageDirectory().absolutePath
        prefixes.add(externalStorage)

        // App's external files directory
        val appExternalDir = context.getExternalFilesDir(null)?.absolutePath
        if (appExternalDir != null) {
            prefixes.add(appExternalDir)
        }

        // App's internal files directory (accessible via proot bind mount)
        prefixes.add(context.filesDir.absolutePath)

        // Common storage mount points
        prefixes.add("/storage/")

        return prefixes
    }

    private fun buildProotDiagnosticMessage(path: String): String {
        return "The selected workspace path '$path' is not accessible by the proot environment. " +
            "Proot can only access paths under external storage (/storage/emulated/0) " +
            "or the app's files directory. " +
            "You can use the app-managed workspace at '${getAppManagedWorkspacePath()}' instead."
    }

    // --- JSON Serialization for Workspace History ---

    internal fun serializeWorkspaceEntries(entries: List<WorkspaceManager.WorkspaceEntry>): String {
        val jsonArray = JSONArray()
        for (entry in entries) {
            val obj = JSONObject().apply {
                put(KEY_URI, entry.uri)
                put(KEY_DISPLAY_NAME, entry.displayName)
                put(KEY_FILESYSTEM_PATH, entry.filesystemPath)
                put(KEY_LAST_USED, entry.lastUsedTimestamp)
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    internal fun parseWorkspaceEntries(json: String): List<WorkspaceManager.WorkspaceEntry> {
        val jsonArray = JSONArray(json)
        val entries = mutableListOf<WorkspaceManager.WorkspaceEntry>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            entries.add(
                WorkspaceManager.WorkspaceEntry(
                    uri = obj.getString(KEY_URI),
                    displayName = obj.getString(KEY_DISPLAY_NAME),
                    filesystemPath = obj.getString(KEY_FILESYSTEM_PATH),
                    lastUsedTimestamp = obj.getLong(KEY_LAST_USED),
                )
            )
        }
        return entries
    }

    private companion object {
        const val APP_MANAGED_WORKSPACE_DIR = "workspace"
        const val MAX_WORKSPACE_HISTORY = 20

        // JSON keys for workspace entry serialization
        const val KEY_URI = "uri"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_FILESYSTEM_PATH = "filesystem_path"
        const val KEY_LAST_USED = "last_used"
    }
}
