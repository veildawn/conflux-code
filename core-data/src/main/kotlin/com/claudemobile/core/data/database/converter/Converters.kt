package com.claudemobile.core.data.database.converter

import androidx.room.TypeConverter
import java.time.Instant

public class Converters {

    @TypeConverter
    public fun fromInstant(instant: Instant?): Long? {
        return instant?.toEpochMilli()
    }

    @TypeConverter
    public fun toInstant(epochMilli: Long?): Instant? {
        return epochMilli?.let { Instant.ofEpochMilli(it) }
    }
}
