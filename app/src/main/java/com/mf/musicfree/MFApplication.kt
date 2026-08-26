package com.mf.musicfree

import android.app.Application
import com.mf.musicfree.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MFApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MFApplication)
            modules(appModule)
        }
    }
}