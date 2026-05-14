package com.claudemobile.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.claudemobile.core.data.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface MessageDao {

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY position ASC")
    public fun getMessagesForSessionFlow(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY position ASC")
    public suspend fun getMessagesForSession(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    public suspend fun getById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(message: MessageEntity)

    @Update
    public suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE session_id = :sessionId")
    public suspend fun deleteBySessionId(sessionId: String)

    @Query("SELECT MAX(position) FROM messages WHERE session_id = :sessionId")
    public suspend fun getMaxPosition(sessionId: String): Int?
}
