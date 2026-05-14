package com.claudemobile.core.bridge.workspace

import io.kotest.core.Tag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.io.File

/**
 * Property-based test for Workspace Boundary Enforcement.
 *
 * **Validates: Requirements 8.6**
 *
 * Property 12: For any filesystem path that is not a descendant of the configured
 * Workspace directory, the proot mount configuration SHALL prevent Claude_CLI from
 * reading or writing that path.
 */
class WorkspaceBoundaryPropertyTest : FunSpec({

    tags(
        Tag("Feature: android-claude-termux-client"),
        Tag("Property 12: Workspace Boundary Enforcement")
    )

    val validator = WorkspaceBoundaryValidator()

    // Generator for valid path segment names (no path separators or null bytes)
    val pathSegmentArb = Arb.string(1..12)
        .map { s -> s.replace('/', '_').replace('\\', '_').replace('\u0000', '_').ifBlank { "dir" } }

    // Generator for workspace paths (absolute paths with 1-4 segments)
    val workspacePathArb = Arb.list(pathSegmentArb, 1..4)
        .map { segments -> "/" + segments.joinToString("/") }

    // Generator for sub-path segments to append inside workspace
    val subPathArb = Arb.list(pathSegmentArb, 1..4)
        .map { segments -> segments.joinToString("/") }

    test("Property 12: paths inside workspace are always allowed") {
        checkAll(
            PropTestConfig(iterations = 100),
            workspacePathArb,
            subPathArb
        ) { workspace, subPath ->
            val targetPath = "$workspace/$subPath"

            validator.isWithinWorkspace(workspace, targetPath) shouldBe true
        }
    }

    test("Property 12: workspace path itself is allowed") {
        checkAll(
            PropTestConfig(iterations = 100),
            workspacePathArb
        ) { workspace ->
            validator.isWithinWorkspace(workspace, workspace) shouldBe true
        }
    }

    test("Property 12: paths outside workspace are always rejected") {
        checkAll(
            PropTestConfig(iterations = 100),
            workspacePathArb,
            workspacePathArb
        ) { workspace, otherPath ->
            // Ensure the other path is not a descendant of workspace
            val canonicalWorkspace = File(workspace).canonicalPath.trimEnd('/')
            val canonicalOther = File(otherPath).canonicalPath.trimEnd('/')

            // Only test when the paths are genuinely different and not nested
            if (canonicalOther != canonicalWorkspace &&
                !canonicalOther.startsWith(canonicalWorkspace + "/")
            ) {
                validator.isWithinWorkspace(workspace, otherPath) shouldBe false
            }
        }
    }

    test("Property 12: path traversal attacks with ../ are rejected") {
        checkAll(
            PropTestConfig(iterations = 100),
            workspacePathArb,
            Arb.int(1..5),
            pathSegmentArb
        ) { workspace, traversalDepth, targetDir ->
            // Build a path that tries to escape via ../
            val traversal = "../".repeat(traversalDepth)
            val attackPath = "$workspace/$traversal$targetDir"

            // The canonical resolution of the attack path should determine the result
            val canonicalWorkspace = File(workspace).canonicalPath.trimEnd('/')
            val canonicalAttack = File(attackPath).canonicalPath.trimEnd('/')

            val expected = canonicalAttack == canonicalWorkspace ||
                canonicalAttack.startsWith(canonicalWorkspace + "/")

            validator.isWithinWorkspace(workspace, attackPath) shouldBe expected
        }
    }

    test("Property 12: sibling directories are always rejected") {
        checkAll(
            PropTestConfig(iterations = 100),
            workspacePathArb,
            pathSegmentArb
        ) { workspace, siblingName ->
            // Navigate to parent and then to a sibling
            val parentPath = File(workspace).parent ?: "/"
            val siblingPath = "$parentPath/$siblingName"

            val canonicalWorkspace = File(workspace).canonicalPath.trimEnd('/')
            val canonicalSibling = File(siblingPath).canonicalPath.trimEnd('/')

            // Only test when the sibling is genuinely outside workspace
            if (canonicalSibling != canonicalWorkspace &&
                !canonicalSibling.startsWith(canonicalWorkspace + "/")
            ) {
                validator.isWithinWorkspace(workspace, siblingPath) shouldBe false
            }
        }
    }

    test("Property 12: paths with prefix matching but not actual descendants are rejected") {
        checkAll(
            PropTestConfig(iterations = 100),
            workspacePathArb,
            pathSegmentArb
        ) { workspace, suffix ->
            // Create a path that starts with the workspace string but is not a child
            // e.g., workspace="/home/user" and target="/home/user-evil/file"
            val trickPath = "${workspace}${suffix}"

            val canonicalWorkspace = File(workspace).canonicalPath.trimEnd('/')
            val canonicalTrick = File(trickPath).canonicalPath.trimEnd('/')

            // This should only be allowed if it's genuinely inside the workspace
            val expected = canonicalTrick == canonicalWorkspace ||
                canonicalTrick.startsWith(canonicalWorkspace + "/")

            validator.isWithinWorkspace(workspace, trickPath) shouldBe expected
        }
    }

    test("Property 12: blank paths are always rejected") {
        checkAll(
            PropTestConfig(iterations = 100),
            workspacePathArb,
            Arb.element("", " ", "  ", "\t", "\n")
        ) { workspace, blankTarget ->
            validator.isWithinWorkspace(workspace, blankTarget) shouldBe false
        }
    }

    test("Property 12: blank workspace always rejects any path") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.element("", " ", "  ", "\t"),
            workspacePathArb
        ) { blankWorkspace, targetPath ->
            validator.isWithinWorkspace(blankWorkspace, targetPath) shouldBe false
        }
    }

    test("Property 12: generateMountArgs always maps to /workspace mount point") {
        checkAll(
            PropTestConfig(iterations = 100),
            workspacePathArb
        ) { workspace ->
            val args = validator.generateMountArgs(workspace)

            // Should produce exactly 2 args: "-b" and "path:/workspace"
            args.size shouldBe 2
            args[0] shouldBe "-b"
            args[1] shouldContain ":${WorkspaceBoundaryValidator.PROOT_MOUNT_POINT}"
        }
    }

    test("Property 12: isWithinProotMountBoundary accepts paths under /workspace") {
        checkAll(
            PropTestConfig(iterations = 100),
            subPathArb
        ) { subPath ->
            val targetPath = "${WorkspaceBoundaryValidator.PROOT_MOUNT_POINT}/$subPath"
            validator.isWithinProotMountBoundary(targetPath) shouldBe true
        }
    }

    test("Property 12: isWithinProotMountBoundary rejects paths outside /workspace") {
        checkAll(
            PropTestConfig(iterations = 100),
            workspacePathArb
        ) { otherPath ->
            val canonicalOther = File(otherPath).canonicalPath.trimEnd('/')
            val canonicalMount = WorkspaceBoundaryValidator.PROOT_MOUNT_POINT

            // Only test when the path is genuinely outside /workspace
            if (canonicalOther != canonicalMount &&
                !canonicalOther.startsWith(canonicalMount + "/")
            ) {
                validator.isWithinProotMountBoundary(otherPath) shouldBe false
            }
        }
    }
})
