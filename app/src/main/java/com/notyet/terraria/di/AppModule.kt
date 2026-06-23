package com.notyet.terraria.di

import android.content.Context
import com.notyet.terraria.core.network.NetworkDetector
import com.notyet.terraria.core.proot.ProotInstaller
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
    fun provideProotInstaller(@ApplicationContext context: Context): ProotInstaller =
        ProotInstaller(context)

    // ProotRunner is NOT provided here — it has @Singleton + @Inject constructor
    // and only depends on ProotInstaller, so Hilt binds it automatically.

    @Provides
    @Singleton
    fun provideNetworkDetector(@ApplicationContext context: Context): NetworkDetector =
        NetworkDetector(context)
}
