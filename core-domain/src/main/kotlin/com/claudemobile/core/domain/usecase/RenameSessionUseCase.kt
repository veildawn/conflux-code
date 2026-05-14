package com.claudemobile.core.domain.usecase

import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.AppResult
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.common.asFailure
import com.claudemobile.core.common.asSuccess
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Renames a session by updating its title and lastActivityAt timestamp.
 */
public class RenameSessionUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {

    /**
     * Updates the title of the session identified by [sessionId] to [newTitle].
     * The repository implementation is expected to also update lastActivityAt.
     *
     * Returns [AppResult.Failure] if the new title is blank or the session ID is blank.
     */
    public suspend operator fun invoke(sessionId: SessionId, newTitle: String): AppResult<Unit> {
        if (sessionId.value.isBlank()) {
            return AppError(
                message = "Session ID must not be empty.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        if (newTitle.isBlank()) {
            return AppError(
                message = "Session title must not be empty.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        conversationRepository.updateSessionTitle(sessionId, newTitle)
        return Unit.asSuccess()
    }
}
