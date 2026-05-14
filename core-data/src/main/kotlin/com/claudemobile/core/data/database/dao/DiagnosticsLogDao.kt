package com.claudemobile.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.claudemobile.core.data.database.entity.DiagnosticsLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface DiagnosticsLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(entry: DiagnosticsLogEntity)

    @Query("SELECT * FROM diagnostics_log ORDER BY timestamp DESC LIMIT :limit")
    public suspend fun getRecentLogs(limit: Int): List<DiagnosticsLogEntity>

    @Query("DELETE FROM diagnostics_log WHERE timestamp < :cutoffTimestamp")
    public suspend fun deleteOldLogs(cutoffTimestamp: Long)

    @Query("SELECT * FROM diagnostics_log WHERE session_id = :sessionId ORDER BY timestamp DESC")
    public fun getBySessionId(sessionId: String): Flow<List<DiagnosticsLogEntity>>

    @Query(
        """
        DELETE FROM diagnostics_log WHERE id IN (
            SELECT id FROM diagnostics_log ORDER BY timestamp ASC
            LIMIT MAX((SELECT COUNT(*) FROM diagnostics_log) - 10000, 0)
        )
        """
    )
    public suspend fun deleteOldest()
}
