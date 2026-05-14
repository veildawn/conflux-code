package com.claudemobile.core.bridge.workspace

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import android.os.Environment
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [WorkspaceManagerImpl].
 *
 * Uses Robolectric to provide real Android framework implementations
 * for Uri, JSONObject, JSONArray, etc.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WorkspaceManagerImplTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var boundaryValidator: WorkspaceBoundaryValidator
    private lateinit var preferencesStore: WorkspacePreferencesStore
    private lateinit var manager: WorkspaceManagerImpl

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        boundaryValidator = WorkspaceBoundaryValidator()
        preferencesStore = mockk(relaxed = true)

        every { context.contentResolver } returns contentResolver
        every { context.getExternalFilesDir(null) } returns File("/storage/emulated/0/Android/data/com.claudemobile/files")
        every { context.filesDir } returns File("/data/data/com.claudemobile/files")

        mockkStatic(Environment::class)
        every { Environment.getExternalStorageDirectory() } returns File("/storage/emulated/0")

        manager = WorkspaceManagerImpl(context, boundaryValidator, preferencesStore)
    }

    @After
    fun tearDown() {
        unmockkStatic(Environment::class)
    }

    // --- translateUriToProotPath ---

    @Test
    fun `translateUriToProotPath returns path directly for file URIs`() {
        val uri = Uri.parse("file:///storage/emulated/0/projects/myapp")
        manager.translateUriToProotPath(uri) shouldBe "/storage/emulated/0/projects/myapp"
    }

    @Test
    fun `translateUriToProotPath returns null for unsupported URI schemes`() {
        val uri = Uri.parse("https://example.com/path")
        manager.translateUriToProotPath(uri) shouldBe null
    }

    @Test
    fun `translateUriToProotPath translates content URI with tree primary pattern`() {
        // Robolectric's Uri.parse handles the path correctly
        // The path segment after /tree/ is "primary:projects/myapp"
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Aprojects%2Fmyapp")
        val result = manager.translateUriToProotPath(uri)
        result shouldBe "/storage/emulated/0/projects/myapp"
    }

    // --- extractPathFromTreeUri ---

    @Test
    fun `extractPathFromTreeUri extracts path from tree primary pattern`() {
        val result = manager.extractPathFromTreeUri("/tree/primary:Documents/code")
        result shouldBe "/storage/emulated/0/Documents/code"
    }

    @Test
    fun `extractPathFromTreeUri extracts path from document primary pattern`() {
        val result = manager.extractPathFromTreeUri("/document/primary:projects/app")
        result shouldBe "/storage/emulated/0/projects/app"
    }

    @Test
    fun `extractPathFromTreeUri handles non-primary volume`() {
        val result = manager.extractPathFromTreeUri("/tree/1234-5678:projects/app")
        result shouldBe "/storage/1234-5678/projects/app"
    }

    @Test
    fun `extractPathFromTreeUri returns null for unrecognized patterns`() {
        val result = manager.extractPathFromTreeUri("/some/other/path")
        result shouldBe null
    }

    @Test
    fun `extractPathFromTreeUri handles empty relative path after primary`() {
        val result = manager.extractPathFromTreeUri("/tree/primary:")
        result shouldBe "/storage/emulated/0/"
    }

    // --- isPathAccessibleByProot ---

    @Test
    fun `isPathAccessibleByProot returns true for paths under external storage`() {
        manager.isPathAccessibleByProot("/storage/emulated/0/projects/myapp") shouldBe true
    }

    @Test
    fun `isPathAccessibleByProot returns true for paths under app external files`() {
        manager.isPathAccessibleByProot(
            "/storage/emulated/0/Android/data/com.claudemobile/files/workspace"
        ) shouldBe true
    }

    @Test
    fun `isPathAccessibleByProot returns true for paths under storage`() {
        manager.isPathAccessibleByProot("/storage/1234-5678/projects") shouldBe true
    }

    @Test
    fun `isPathAccessibleByProot returns false for blank paths`() {
        manager.isPathAccessibleByProot("") shouldBe false
        manager.isPathAccessibleByProot("   ") shouldBe false
    }

    @Test
    fun `isPathAccessibleByProot returns false for relative paths`() {
        manager.isPathAccessibleByProot("relative/path") shouldBe false
    }

    @Test
    fun `isPathAccessibleByProot returns false for paths outside accessible areas`() {
        manager.isPathAccessibleByProot("/proc/self/maps") shouldBe false
        manager.isPathAccessibleByProot("/sys/kernel") shouldBe false
    }

    // --- getAppManagedWorkspacePath ---

    @Test
    fun `getAppManagedWorkspacePath returns path under app external files directory`() {
        val result = manager.getAppManagedWorkspacePath()
        result shouldBe "/storage/emulated/0/Android/data/com.claudemobile/files/workspace"
    }

    // --- generateMountArgs ---

    @Test
    fun `generateMountArgs generates mount args with workspace mount point`() {
        val result = manager.generateMountArgs("/storage/emulated/0/projects")
        result shouldHaveSize 2
        result[0] shouldBe "-b"
        result[1] shouldContain ":/workspace"
    }

    @Test
    fun `generateMountArgs uses canonical workspace path in mount args`() {
        val result = manager.generateMountArgs("/storage/emulated/0/projects")
        result[1] shouldContain "/storage/emulated/0/projects"
    }

    // --- workspace history ---

    @Test
    fun `getPreviousWorkspaces returns empty list when no entries stored`() {
        every { preferencesStore.getWorkspaceEntries() } returns null
        manager.getPreviousWorkspaces().shouldBeEmpty()
    }

    @Test
    fun `getPreviousWorkspaces returns empty list on invalid JSON`() {
        every { preferencesStore.getWorkspaceEntries() } returns "invalid json"
        manager.getPreviousWorkspaces().shouldBeEmpty()
    }

    @Test
    fun `addWorkspaceToHistory stores entry`() {
        val entry = WorkspaceManager.WorkspaceEntry(
            uri = "content://test/uri",
            displayName = "My Project",
            filesystemPath = "/storage/emulated/0/projects/myproject",
            lastUsedTimestamp = 1000L,
        )

        every { preferencesStore.getWorkspaceEntries() } returns null

        manager.addWorkspaceToHistory(entry)

        verify {
            preferencesStore.setWorkspaceEntries(any())
        }
    }

    @Test
    fun `addWorkspaceToHistory removes duplicate URIs`() {
        val existingJson = manager.serializeWorkspaceEntries(
            listOf(
                WorkspaceManager.WorkspaceEntry(
                    uri = "content://test/uri",
                    displayName = "Old Name",
                    filesystemPath = "/old/path",
                    lastUsedTimestamp = 500L,
                )
            )
        )
        every { preferencesStore.getWorkspaceEntries() } returns existingJson

        val updatedEntry = WorkspaceManager.WorkspaceEntry(
            uri = "content://test/uri",
            displayName = "New Name",
            filesystemPath = "/new/path",
            lastUsedTimestamp = 1000L,
        )

        manager.addWorkspaceToHistory(updatedEntry)

        verify {
            preferencesStore.setWorkspaceEntries(match { json ->
                val entries = manager.parseWorkspaceEntries(json)
                entries.size == 1 && entries[0].displayName == "New Name"
            })
        }
    }

    @Test
    fun `getPreviousWorkspaces orders entries by most recently used first`() {
        val entries = listOf(
            WorkspaceManager.WorkspaceEntry(
                uri = "content://test/old",
                displayName = "Old",
                filesystemPath = "/old",
                lastUsedTimestamp = 100L,
            ),
            WorkspaceManager.WorkspaceEntry(
                uri = "content://test/new",
                displayName = "New",
                filesystemPath = "/new",
                lastUsedTimestamp = 200L,
            ),
        )
        every { preferencesStore.getWorkspaceEntries() } returns
            manager.serializeWorkspaceEntries(entries)

        val result = manager.getPreviousWorkspaces()
        result shouldHaveSize 2
        result[0].displayName shouldBe "New"
        result[1].displayName shouldBe "Old"
    }

    @Test
    fun `removeWorkspaceFromHistory removes entry and releases URI permission`() {
        val entries = listOf(
            WorkspaceManager.WorkspaceEntry(
                uri = "content://test/a",
                displayName = "A",
                filesystemPath = "/a",
                lastUsedTimestamp = 100L,
            ),
            WorkspaceManager.WorkspaceEntry(
                uri = "content://test/b",
                displayName = "B",
                filesystemPath = "/b",
                lastUsedTimestamp = 200L,
            ),
        )
        every { preferencesStore.getWorkspaceEntries() } returns
            manager.serializeWorkspaceEntries(entries)

        manager.removeWorkspaceFromHistory("content://test/a")

        // Verify URI permission release was attempted
        verify {
            contentResolver.releasePersistableUriPermission(
                Uri.parse("content://test/a"),
                any()
            )
        }

        verify {
            preferencesStore.setWorkspaceEntries(match { json ->
                val parsed = manager.parseWorkspaceEntries(json)
                parsed.size == 1 && parsed[0].uri == "content://test/b"
            })
        }
    }

    @Test
    fun `clearWorkspaceHistory releases all URI permissions`() {
        val entries = listOf(
            WorkspaceManager.WorkspaceEntry(
                uri = "content://test/a",
                displayName = "A",
                filesystemPath = "/a",
                lastUsedTimestamp = 100L,
            ),
            WorkspaceManager.WorkspaceEntry(
                uri = "content://test/b",
                displayName = "B",
                filesystemPath = "/b",
                lastUsedTimestamp = 200L,
            ),
        )
        every { preferencesStore.getWorkspaceEntries() } returns
            manager.serializeWorkspaceEntries(entries)

        manager.clearWorkspaceHistory()

        verify {
            contentResolver.releasePersistableUriPermission(
                Uri.parse("content://test/a"),
                any()
            )
        }
        verify {
            contentResolver.releasePersistableUriPermission(
                Uri.parse("content://test/b"),
                any()
            )
        }
        verify { preferencesStore.clearWorkspaceEntries() }
    }

    // --- hasPersistedPermission ---

    @Test
    fun `hasPersistedPermission returns true when permission is held`() {
        val uri = Uri.parse("content://test/workspace")
        val permission = mockk<UriPermission>()
        every { permission.uri } returns uri
        every { permission.isReadPermission } returns true
        every { permission.isWritePermission } returns true
        every { contentResolver.persistedUriPermissions } returns listOf(permission)

        manager.hasPersistedPermission("content://test/workspace") shouldBe true
    }

    @Test
    fun `hasPersistedPermission returns false when no permission is held`() {
        every { contentResolver.persistedUriPermissions } returns emptyList()

        manager.hasPersistedPermission("content://test/workspace") shouldBe false
    }

    @Test
    fun `hasPersistedPermission returns false when only read permission is held`() {
        val uri = Uri.parse("content://test/workspace")
        val permission = mockk<UriPermission>()
        every { permission.uri } returns uri
        every { permission.isReadPermission } returns true
        every { permission.isWritePermission } returns false
        every { contentResolver.persistedUriPermissions } returns listOf(permission)

        manager.hasPersistedPermission("content://test/workspace") shouldBe false
    }

    @Test
    fun `hasPersistedPermission returns false for empty URI strings`() {
        manager.hasPersistedPermission("") shouldBe false
    }

    // --- JSON serialization ---

    @Test
    fun `serializeWorkspaceEntries and parseWorkspaceEntries round-trip`() {
        val entries = listOf(
            WorkspaceManager.WorkspaceEntry(
                uri = "content://com.android.externalstorage.documents/tree/primary:projects",
                displayName = "projects",
                filesystemPath = "/storage/emulated/0/projects",
                lastUsedTimestamp = 1234567890L,
            ),
            WorkspaceManager.WorkspaceEntry(
                uri = "content://com.android.externalstorage.documents/tree/primary:code",
                displayName = "code",
                filesystemPath = "/storage/emulated/0/code",
                lastUsedTimestamp = 1234567800L,
            ),
        )

        val json = manager.serializeWorkspaceEntries(entries)
        val parsed = manager.parseWorkspaceEntries(json)

        parsed shouldHaveSize 2
        parsed[0].uri shouldBe entries[0].uri
        parsed[0].displayName shouldBe entries[0].displayName
        parsed[0].filesystemPath shouldBe entries[0].filesystemPath
        parsed[0].lastUsedTimestamp shouldBe entries[0].lastUsedTimestamp
        parsed[1].uri shouldBe entries[1].uri
        parsed[1].displayName shouldBe entries[1].displayName
        parsed[1].filesystemPath shouldBe entries[1].filesystemPath
        parsed[1].lastUsedTimestamp shouldBe entries[1].lastUsedTimestamp
    }

    @Test
    fun `serializeWorkspaceEntries handles empty list`() {
        val json = manager.serializeWorkspaceEntries(emptyList())
        val parsed = manager.parseWorkspaceEntries(json)
        parsed.shouldBeEmpty()
    }

    // --- selectWorkspace ---

    @Test
    fun `selectWorkspace returns Failure when URI cannot be translated`() {
        val uri = Uri.parse("https://example.com/not-a-file")
        val result = manager.selectWorkspace(uri)
        result.shouldBeInstanceOf<WorkspaceManager.SelectionResult.Failure>()
    }

    @Test
    fun `selectWorkspace returns NotAccessibleByProot for inaccessible paths`() {
        val uri = Uri.parse("file:///proc/self/maps")
        every { preferencesStore.getWorkspaceEntries() } returns null

        val result = manager.selectWorkspace(uri)
        result.shouldBeInstanceOf<WorkspaceManager.SelectionResult.NotAccessibleByProot>()
    }

    @Test
    fun `selectWorkspace returns TemporaryAccess when takePersistableUriPermission fails`() {
        val uri = Uri.parse("file:///storage/emulated/0/projects/myapp")
        every { preferencesStore.getWorkspaceEntries() } returns null
        every {
            contentResolver.takePersistableUriPermission(uri, any())
        } throws SecurityException("Not persistable")

        val result = manager.selectWorkspace(uri)
        result.shouldBeInstanceOf<WorkspaceManager.SelectionResult.TemporaryAccess>()
        (result as WorkspaceManager.SelectionResult.TemporaryAccess).workspacePath shouldBe
            "/storage/emulated/0/projects/myapp"
    }

    @Test
    fun `selectWorkspace returns Success when permission is persisted`() {
        val uri = Uri.parse("file:///storage/emulated/0/projects/myapp")
        every { preferencesStore.getWorkspaceEntries() } returns null

        val result = manager.selectWorkspace(uri)
        result.shouldBeInstanceOf<WorkspaceManager.SelectionResult.Success>()
        (result as WorkspaceManager.SelectionResult.Success).workspacePath shouldBe
            "/storage/emulated/0/projects/myapp"
        result.isPersistable shouldBe true
    }

    @Test
    fun `selectWorkspace TemporaryAccess message informs about next launch inaccessibility`() {
        val uri = Uri.parse("file:///storage/emulated/0/projects/myapp")
        every { preferencesStore.getWorkspaceEntries() } returns null
        every {
            contentResolver.takePersistableUriPermission(uri, any())
        } throws SecurityException("Not persistable")

        val result = manager.selectWorkspace(uri)
        result.shouldBeInstanceOf<WorkspaceManager.SelectionResult.TemporaryAccess>()
        (result as WorkspaceManager.SelectionResult.TemporaryAccess).message shouldContain
            "inaccessible on the next app launch"
    }

    @Test
    fun `selectWorkspace NotAccessibleByProot diagnostic suggests app-managed workspace`() {
        val uri = Uri.parse("file:///proc/self/maps")
        every { preferencesStore.getWorkspaceEntries() } returns null

        val result = manager.selectWorkspace(uri)
        result.shouldBeInstanceOf<WorkspaceManager.SelectionResult.NotAccessibleByProot>()
        (result as WorkspaceManager.SelectionResult.NotAccessibleByProot)
            .diagnosticMessage shouldContain "app-managed workspace"
    }

    // --- releasePersistablePermission ---

    @Test
    fun `releasePersistablePermission releases permission for valid URI`() {
        val uriString = "content://test/workspace"
        manager.releasePersistablePermission(uriString)

        verify {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                any()
            )
        }
    }

    @Test
    fun `releasePersistablePermission handles SecurityException gracefully`() {
        val uriString = "content://test/workspace"
        every {
            contentResolver.releasePersistableUriPermission(any(), any())
        } throws SecurityException("Not held")

        // Should not throw
        manager.releasePersistablePermission(uriString)
    }
}
