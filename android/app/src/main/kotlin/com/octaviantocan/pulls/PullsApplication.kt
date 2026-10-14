package com.octaviantocan.pulls

import android.app.Application
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.sound.Sounds

class PullsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Sounds.install(this, AppSettings.get(this))
    }
}
