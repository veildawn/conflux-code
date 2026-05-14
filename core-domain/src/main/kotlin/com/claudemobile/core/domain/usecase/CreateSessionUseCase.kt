package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.common.asSuccess
import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Creates a new conversation session after validating that the workspace path is non-empty
 * and that an active Provider_Profile is configured.
 */
public class CreateSessionUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val providerProfileStore: ProviderProfileStore,
) {

    /**
     * Creates a new session with the given [title] and [workspacePath].
     *
     * Returns [AppResult.Failure] if:
     * - No active Provider_Profile is set (ErrorCode.PERMISSION_DENIED)
     * - The workspace path is blank (ErrorCode.INVALID_ARGUMENT)
     */
    public suspend operator fun invoke(title: String, workspacePath: String): AppResult<Session> {
        val activeProfile = providerProfileStore.getActive()
        if (activeProfile == null) {
            return AppError(
                message = "No active provider configured. Please select a provider before creating a session.",
                code = ErrorCode.PERMISSION_DENIED,
            ).asFailure()
        }

        if (workspacePath.isBlank()) {
            return AppError(
                message = "Workspace path must not be empty.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        val session = conversationRepository.createSession(
            title = title.ifBlank { "New Session" },
            workspacePath = workspacePath,
        )

        return session.asSuccess()
    }
}
