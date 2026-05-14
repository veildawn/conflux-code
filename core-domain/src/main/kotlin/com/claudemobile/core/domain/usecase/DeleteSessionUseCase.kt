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
 * Deletes a session and all its associated messages in a single transaction.
 */
public class DeleteSessionUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {

    /**
     * Deletes the session identified by [sessionId] along with all its messages.
     *
     * Returns [AppResult.Failure] if the session ID is blank.
     */
    public suspend operator fun invoke(sessionId: SessionId): AppResult<Unit> {
        if (sessionId.value.isBlank()) {
            return AppError(
                message = "Session ID must not be empty.",
                code = ErrorCode.INVALID_ARGUMENT,
            ).asFailure()
        }

        conversationRepository.deleteSession(sessionId)
        return Unit.asSuccess()
    }
}
