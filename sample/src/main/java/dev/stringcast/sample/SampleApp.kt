package dev.stringcast.sample

import android.app.Application
import dev.stringcast.sdk.StringCast
import dev.stringcast.sdk.StringCastConfig

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        StringCast.init(
            this,
            StringCastConfig(
                projectId = "p_demo",
                sdkKey = "pk_demo_local",
                // The Android emulator reaches the host machine's localhost at 10.0.2.2.
                baseUrl = "http://10.0.2.2:8787",
                // baseUrl = "https://console.stringcast.app", // production (also the default if baseUrl is omitted)
                logging = true,
                // draftMode defaults to true for debuggable builds.
            ),
        )
    }
}
