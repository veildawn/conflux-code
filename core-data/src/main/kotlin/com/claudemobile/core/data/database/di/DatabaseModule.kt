package com.claudemobile.core.data.database.di

import android.content.Context
import androidx.room.Room
import com.claudemobile.core.data.database.AppDatabase
import com.claudemobile.core.data.database.dao.DiagnosticsLogDao
import com.claudemobile.core.data.database.dao.MessageDao
import com.claudemobile.core.data.database.dao.SessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public object DatabaseModule {

    @Provides
    @Singleton
    public fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).build()
    }

    @Provides
    public fun provideSessionDao(database: AppDatabase): SessionDao {
        return database.sessionDao()
    }

    @Provides
    public fun provideMessageDao(database: AppDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    public fun provideDiagnosticsLogDao(database: AppDatabase): DiagnosticsLogDao {
        return database.diagnosticsLogDao()
    }
}
