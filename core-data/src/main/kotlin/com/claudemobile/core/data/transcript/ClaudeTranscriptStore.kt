package com.claudemobile.core.data.transcript

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Filesystem access for Claude Code session transcripts inside the bundled rootfs.
 *
 * Claude is currently launched with `-w /root`, so its sessions live under the
 * encoded `/root` project directory, for example `~/.claude/projects/-root/<session>.jsonl`.
 */
@Singleton
public class ClaudeTranscriptStore private constructor(
    private val sessionsDir: File,
) {
    @Inject
    public constructor(
        @ApplicationContext context: Context,
    ) : this(
        sessionsDir = File(
            context.filesDir,
            "rootfs/root/.claude/projects/$DEFAULT_PROJECT_KEY",
        ),
    )

    internal constructor(sessionsDir: File, @Suppress("UNUSED_PARAMETER") forTests: Unit = Unit) :
        this(sessionsDir = sessionsDir)

    /**
     * Returns the transcript file for [sessionId], creating the parent directory
     * lazily when a write is requested.
     */
    public fun transcriptFile(sessionId: String): File =
        File(sessionsDir, "$sessionId.jsonl")

    /**
     * Lists top-level Claude session transcript files for the active project.
     */
    public fun listSessionFiles(): List<File> =
        sessionsDir
            .takeIf(File::exists)
            ?.listFiles()
            ?.filter { file -> file.isFile && file.extension == "jsonl" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()

    /**
     * Appends a single JSON line to a Claude transcript file.
     */
    public fun appendLine(sessionId: String, jsonLine: String) {
        val file = transcriptFile(sessionId)
        file.parentFile?.mkdirs()
        file.appendText(jsonLine)
        file.appendText("\n")
    }

    /**
     * Deletes the main transcript file for a session.
     */
    public fun deleteSession(sessionId: String): Boolean =
        transcriptFile(sessionId).delete()

    private companion object {
        const val DEFAULT_PROJECT_KEY: String = "-root"
    }
}
