package com.claudemobile.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diagnostics_log")
public data class DiagnosticsLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "session_id")
    val sessionId: String?,

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "message")
    val message: String,

    @ColumnInfo(name = "details")
    val details: String?,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long
)
