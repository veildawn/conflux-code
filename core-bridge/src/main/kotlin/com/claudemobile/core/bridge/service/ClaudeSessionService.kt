package com.claudemobile.core.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.claudemobile.core.domain.bridge.CliBridge
import com.claudemobile.core.domain.bridge.ProcessState
import com.claudemobile.core.domain.model.MessageStatus
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import com.claudemobile.core.domain.repository.DiagnosticsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps Claude CLI processes alive while the app is backgrounded.
 *
 * Responsibilities:
 * - Shows a persistent notification with session title and turn status
 * - Holds Bridge references in memory so streaming continues in background
 * - Stops itself when all active processes terminate
 * - Handles notification tap to open the associated session
 * - Detects OS kill on next launch and marks in-flight messages as killed_by_os
 *
 * Uses foregroundServiceType="dataSync" and declares FOREGROUND_SERVICE_DATA_SYNC permission.
 *
 * Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6
 */
@AndroidEntryPoint
public class ClaudeSessionService : LifecycleService() {

    @Inject
    internal lateinit var cliBridge: CliBridge

    @Inject
    internal lateinit var conversationRepository: ConversationRepository

    @Inject
    internal lateinit var diagnosticsRepository: DiagnosticsRepository

    @Inject
    internal lateinit var preferencesStore: ServicePreferencesStore

    private var processStateJob: Job? = null
    private val activeSessions = mutableMapOf<String, SessionInfo>()
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_SESSION -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                val sessionTitle = intent.getStringExtra(EXTRA_SESSION_TITLE) ?: "Claude Session"
                handleStartSession(sessionId, sessionTitle)
            }
            ACTION_STOP_SESSION -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                handleStopSession(sessionId)
            }
            ACTION_UPDATE_STATUS -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                val status = intent.getStringExtra(EXTRA_TURN_STATUS) ?: ""
                handleUpdateStatus(sessionId, status)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        processStateJob?.cancel()
        processStateJob = null
        markActiveSessionsAsKilled()
        super.onDestroy()
    }

    private fun handleStartSession(sessionId: String, title: String) {
        val sessionInfo = SessionInfo(sessionId = sessionId, title = title, turnStatus = "Running")
        activeSessions[sessionId] = sessionInfo

        // Record that this session has an active process (for OS kill detection)
        preferencesStore.setActiveSessions(activeSessions.keys.toSet())

        // Start as foreground with notification
        startForeground(NOTIFICATION_ID, buildNotification())

        // Monitor process state to auto-stop when all processes terminate
        if (processStateJob == null) {
            processStateJob = lifecycleScope.launch {
                cliBridge.processState.collectLatest { state ->
                    handleProcessStateChange(state)
                }
            }
        }
    }

    /**
     * Handles process state transitions. When the process terminates,
     * removes the associated session and stops the service if no sessions remain.
     */
    private fun handleProcessStateChange(state: ProcessState) {
        when (state) {
            ProcessState.TERMINATED -> {
                // When the process terminates, remove all tracked sessions
                // since we only support one process at a time (single-process invariant)
                val sessionIds = activeSessions.keys.toList()
                for (sessionId in sessionIds) {
                    handleStopSession(sessionId)
                }
            }
            ProcessState.RUNNING -> {
                updateNotificationStatus("Running")
            }
            ProcessState.STOPPING -> {
                updateNotificationStatus("Stopping...")
            }
            ProcessState.STARTING -> {
                updateNotificationStatus("Starting...")
            }
            ProcessState.IDLE -> {
                // If we're idle but have active sessions, it means all processes ended
                if (activeSessions.isNotEmpty()) {
                    val sessionIds = activeSessions.keys.toList()
                    for (sessionId in sessionIds) {
                        handleStopSession(sessionId)
                    }
                }
            }
        }
    }

    private fun handleStopSession(sessionId: String) {
        activeSessions.remove(sessionId)

        if (activeSessions.isEmpty()) {
            preferencesStore.clearActiveSessions()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            preferencesStore.setActiveSessions(activeSessions.keys.toSet())
            // Update notification to show remaining sessions
            notificationManager?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun handleUpdateStatus(sessionId: String, status: String) {
        activeSessions[sessionId]?.let { info ->
            activeSessions[sessionId] = info.copy(turnStatus = status)
            notificationManager?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun updateNotificationStatus(status: String) {
        activeSessions.entries.firstOrNull()?.let { (id, info) ->
            activeSessions[id] = info.copy(turnStatus = status)
        }
        notificationManager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val sessionInfo = activeSessions.values.firstOrNull()
        val title = sessionInfo?.title ?: "Claude Session"
        val status = sessionInfo?.turnStatus ?: "Active"
        val contentText = if (activeSessions.size > 1) {
            "$status • ${activeSessions.size} active sessions"
        } else {
            status
        }

        val tapIntent = createSessionTapIntent(sessionInfo?.sessionId)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Creates a PendingIntent that opens the app to the associated session when
     * the notification is tapped.
     *
     * Requirement 7.4: Notification tap opens the associated session in the UI.
     */
    private fun createSessionTapIntent(sessionId: String?): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) {
                putExtra(EXTRA_OPEN_SESSION_ID, sessionId)
            }
        } ?: Intent()

        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_SESSION,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Claude CLI is running in the background"
            setShowBadge(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    /**
     * Marks active sessions as killed when the service is destroyed by the OS.
     * This is called from onDestroy which fires when the OS kills the service.
     * On next app launch, detectAndHandleOsKill() will pick up these sessions
     * and mark their in-flight messages as killed_by_os.
     *
     * Requirement 7.6: OS kill detection and recovery.
     */
    private fun markActiveSessionsAsKilled() {
        if (activeSessions.isNotEmpty()) {
            preferencesStore.setKilledSessions(activeSessions.keys.toSet())
            preferencesStore.clearActiveSessions()
        }
    }

    internal data class SessionInfo(
        val sessionId: String,
        val title: String,
        val turnStatus: String
    )

    public companion object {
        public const val ACTION_START_SESSION: String = "com.claudemobile.action.START_SESSION"
        public const val ACTION_STOP_SESSION: String = "com.claudemobile.action.STOP_SESSION"
        public const val ACTION_UPDATE_STATUS: String = "com.claudemobile.action.UPDATE_STATUS"

        public const val EXTRA_SESSION_ID: String = "extra_session_id"
        public const val EXTRA_SESSION_TITLE: String = "extra_session_title"
        public const val EXTRA_TURN_STATUS: String = "extra_turn_status"
        public const val EXTRA_OPEN_SESSION_ID: String = "extra_open_session_id"

        internal const val CHANNEL_ID = "claude_session_channel"
        internal const val CHANNEL_NAME = "Claude CLI Sessions"
        internal const val NOTIFICATION_ID = 1001
        internal const val REQUEST_CODE_OPEN_SESSION = 2001

        internal const val PREF_KILLED_SESSIONS = "killed_sessions"

        /**
         * Detects if the OS killed the service on a previous run and marks
         * any in-flight assistant messages as killed_by_os.
         *
         * Should be called on app launch (e.g., from Application.onCreate).
         *
         * Requirement 7.6: On next App launch the Bridge SHALL detect the missing
         * process and mark any in-flight assistant Messages as killed_by_os.
         *
         * @param context Application context
         * @param preferencesStore The service preferences store
         * @param conversationRepository Repository to update message statuses
         * @param diagnosticsRepository Repository to log the kill event
         */
        public suspend fun detectAndHandleOsKill(
            context: Context,
            preferencesStore: ServicePreferencesStore,
            conversationRepository: ConversationRepository,
            diagnosticsRepository: DiagnosticsRepository,
        ) {
            detectAndHandleOsKillInternal(
                preferencesStore = preferencesStore,
                conversationRepository = conversationRepository,
                diagnosticsRepository = diagnosticsRepository,
            )
        }

        /**
         * Internal implementation of OS kill detection, testable without Android Context.
         */
        internal suspend fun detectAndHandleOsKillInternal(
            preferencesStore: ServicePreferencesStore,
            conversationRepository: ConversationRepository,
            diagnosticsRepository: DiagnosticsRepository,
        ) {
            val killedSessions = preferencesStore.getKilledSessions()

            if (killedSessions.isNullOrEmpty()) return

            // Clear the killed sessions flag
            preferencesStore.clearKilledSessions()

            // Mark in-flight messages as killed_by_os for each affected session
            for (sessionId in killedSessions) {
                try {
                    val messages = conversationRepository.getMessages(SessionId(sessionId))
                    val inFlightMessages = messages.filter { msg ->
                        msg.status == MessageStatus.STREAMING || msg.status == MessageStatus.SENDING
                    }

                    for (message in inFlightMessages) {
                        conversationRepository.updateMessageStatus(
                            message.id,
                            MessageStatus.ERROR
                        )
                        // Update content to indicate OS kill
                        val updatedContent = message.content +
                            "\n\n[Process killed by OS — the system terminated the background service]"
                        conversationRepository.updateMessageContent(message.id, updatedContent)
                    }

                    // Log the kill event
                    diagnosticsRepository.logEvent(
                        sessionId = sessionId,
                        eventType = "bridge_lifecycle",
                        message = "Process killed by OS",
                        details = "The operating system terminated the foreground service. " +
                            "${inFlightMessages.size} in-flight message(s) were marked as killed_by_os."
                    )
                } catch (_: Exception) {
                    // Best effort — don't crash on startup
                }
            }
        }

        /**
         * Creates an intent to start the service for a given session.
         */
        public fun startIntent(context: Context, sessionId: String, sessionTitle: String): Intent {
            return Intent(context, ClaudeSessionService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_SESSION_TITLE, sessionTitle)
            }
        }

        /**
         * Creates an intent to stop tracking a session in the service.
         */
        public fun stopIntent(context: Context, sessionId: String): Intent {
            return Intent(context, ClaudeSessionService::class.java).apply {
                action = ACTION_STOP_SESSION
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
        }

        /**
         * Creates an intent to update the turn status for a session.
         */
        public fun updateStatusIntent(
            context: Context,
            sessionId: String,
            turnStatus: String
        ): Intent {
            return Intent(context, ClaudeSessionService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_TURN_STATUS, turnStatus)
            }
        }
    }
}
