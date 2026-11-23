package com.reelplayer.app

import android.app.Application
import android.util.Log
import java.io.File
import java.util.Date

/** Saves the details of any crash so the next launch can show them (and they can be copied). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile(this).writeText(
                    "HarmooPlayer ${BuildConfig.VERSION_NAME} crashed ${Date()}\n" +
                        "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n" +
                        Log.getStackTraceString(error)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "last_crash.txt")
    }
}
