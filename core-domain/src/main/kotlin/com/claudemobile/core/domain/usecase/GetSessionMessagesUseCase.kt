package com.claudemobile.core.domain.usecase

import com.claudemobile.core.domain.model.Message
import com.claudemobile.core.domain.model.SessionId
import com.claudemobile.core.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Returns all messages for a given session in ascending position order.
 *
 * Provides both a reactive [Flow] for observing changes and a one-shot suspend function.
 */
public class GetSessionMessagesUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {

    /**
     * Returns a reactive [Flow] of messages for the session identified by [sessionId],
     * ordered by position ascending. Emits a new list whenever messages change.
     */
    public operator fun invoke(sessionId: SessionId): Flow<List<Message>> {
        return conversationRepository.getMessagesFlow(sessionId)
    }

    /**
     * Returns the current list of messages for the session identified by [sessionId],
     * ordered by position ascending.
     */
    public suspend fun getOnce(sessionId: SessionId): List<Message> {
        return conversationRepository.getMessages(sessionId)
    }
}
