package com.swordfish.lemuroid.app.shared.startup

import android.content.Context
import android.os.StrictMode
import android.util.Log
import androidx.startup.Initializer
import com.swordfish.lemuroid.BuildConfig
import timber.log.Timber

class DebugInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            enableStrictMode()
        } else {
            // 在发行版中仅保留 I/W/E 级别的日志
            Timber.plant(object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    // 只记录 INFO、WARN 和 ERROR 级别的日志
                    if (priority == Log.INFO || priority == Log.WARN || priority == Log.ERROR) {
                        super.log(priority, tag, message, t)
                    }
                }
            })
        }
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}
