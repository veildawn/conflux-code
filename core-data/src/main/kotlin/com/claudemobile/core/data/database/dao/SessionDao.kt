package com.claudemobile.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.claudemobile.core.data.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY last_activity_at DESC")
    public fun getAllSessionsFlow(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    public suspend fun getById(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(session: SessionEntity)

    @Update
    public suspend fun update(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    public suspend fun deleteById(id: String)
}
