package com.claudemobile.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.claudemobile.core.data.database.converter.Converters
import com.claudemobile.core.data.database.dao.DiagnosticsLogDao
import com.claudemobile.core.data.database.dao.MessageDao
import com.claudemobile.core.data.database.dao.SessionDao
import com.claudemobile.core.data.database.entity.DiagnosticsLogEntity
import com.claudemobile.core.data.database.entity.MessageEntity
import com.claudemobile.core.data.database.entity.SessionEntity

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        DiagnosticsLogEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
public abstract class AppDatabase : RoomDatabase() {

    public abstract fun sessionDao(): SessionDao

    public abstract fun messageDao(): MessageDao

    public abstract fun diagnosticsLogDao(): DiagnosticsLogDao

    public companion object {
        public const val DATABASE_NAME: String = "claude_mobile_db"
    }
}
