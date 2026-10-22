package com.chatwithhal.example

import android.app.Application
import com.chatwithhal.sdk.HalChat
import com.chatwithhal.sdk.HalConfiguration

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Point at a local server with:
        // adb shell setprop debug.hal.project <id>; adb shell setprop debug.hal.server http://10.0.2.2:8001
        HalChat.configure(
            this,
            projectId = systemProperty("debug.hal.project") ?: "your-project-id",
            serverUrl = systemProperty("debug.hal.server") ?: HalConfiguration.DEFAULT_SERVER_URL,
        )
    }

    @Suppress("PrivateApi")
    private fun systemProperty(name: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as String
    } catch (_: Exception) {
        null
    }?.takeIf { it.isNotEmpty() }
}
