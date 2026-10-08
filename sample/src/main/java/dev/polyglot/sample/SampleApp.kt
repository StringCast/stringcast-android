package dev.polyglot.sample

import android.app.Application
import dev.polyglot.sdk.Polyglot
import dev.polyglot.sdk.PolyglotConfig

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Polyglot.init(
            this,
            PolyglotConfig(
                projectId = "p_demo",
                sdkKey = "pk_demo_local",
                // The Android emulator reaches the host machine's localhost at 10.0.2.2.
                baseUrl = "http://10.0.2.2:8787",
                logging = true,
                // draftMode defaults to true for debuggable builds.
            ),
        )
    }
}
