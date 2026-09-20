package com.aruncs.musicsync.di

import android.content.Context
import com.aruncs.musicsync.client.DesktopApiClient
import com.aruncs.musicsync.client.SyncManager
import com.aruncs.musicsync.data.AppPreferences
import com.aruncs.musicsync.data.PlaylistManager
import com.aruncs.musicsync.player.AudioPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppPreferences(@ApplicationContext context: Context): AppPreferences {
        return AppPreferences(context)
    }

    @Provides
    @Singleton
    fun provideSyncManager(@ApplicationContext context: Context): SyncManager {
        return SyncManager(context)
    }

    @Provides
    @Singleton
    fun providePlaylistManager(@ApplicationContext context: Context): PlaylistManager {
        return PlaylistManager(context)
    }

    @Provides
    @Singleton
    fun provideDesktopApiClient(): DesktopApiClient {
        return DesktopApiClient()
    }

    @Provides
    @Singleton
    fun provideAudioPlayer(): AudioPlayer {
        return AudioPlayer()
    }
}
