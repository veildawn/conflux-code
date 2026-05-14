package com.claudemobile.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.claudemobile.core.domain.repository.SettingsStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
public abstract class SettingsModule {

    @Binds
    @Singleton
    internal abstract fun bindSettingsStore(impl: SettingsStoreImpl): SettingsStore

    public companion object {

        private const val SETTINGS_DATASTORE_NAME = "app_settings"

        @Provides
        @Singleton
        public fun provideSettingsDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> {
            return PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile(SETTINGS_DATASTORE_NAME)
            }
        }
    }
}
