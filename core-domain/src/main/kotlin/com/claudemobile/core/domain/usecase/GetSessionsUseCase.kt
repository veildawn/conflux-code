package com.claudemobile.core.domain.usecase

import com.claudemobile.core.domain.model.Session
import com.claudemobile.core.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Returns a reactive stream of all sessions ordered by lastActivityAt descending.
 */
public class GetSessionsUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {

    /**
     * Returns a [Flow] emitting the current list of sessions whenever the data changes.
     * Sessions are ordered by last activity timestamp in descending order.
     */
    public operator fun invoke(): Flow<List<Session>> {
        return conversationRepository.getSessions()
    }
}
