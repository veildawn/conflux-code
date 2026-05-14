package com.claudemobile.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("session_id")]
)
public data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "position")
    val position: Int,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "tool_name")
    val toolName: String? = null,

    @ColumnInfo(name = "tool_arguments")
    val toolArguments: String? = null,

    @ColumnInfo(name = "tool_result")
    val toolResult: String? = null,

    @ColumnInfo(name = "tool_status")
    val toolStatus: String? = null
)
