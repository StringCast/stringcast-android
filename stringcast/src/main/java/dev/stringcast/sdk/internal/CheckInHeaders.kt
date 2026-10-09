package dev.stringcast.sdk.internal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Check-in headers (contract §4.2), sent on `/manifest` and `/missing` only — never on bundle
 * downloads (CDN). They identify the app, not the user: no device IDs or personal data.
 */
internal object CheckInHeaders {
    const val PLATFORM = "android"

    const val H_PLATFORM = "X-StringCast-Platform"
    const val H_APP_ID = "X-StringCast-App-Id"
    const val H_APP_VERSION = "X-StringCast-App-Version"
    const val H_SDK_VERSION = "X-StringCast-SDK-Version"
    const val H_LANGUAGE = "X-StringCast-Language"

    /** Builds the header map; null/blank values (after ASCII sanitising) are omitted. */
    fun build(appId: String?, appVersion: String?, sdkVersion: String?, language: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>(5)
        fun put(name: String, value: String?) {
            val v = asciiSafe(value)
            if (v.isNotEmpty()) out[name] = v
        }
        put(H_PLATFORM, PLATFORM)
        put(H_APP_ID, appId)
        put(H_APP_VERSION, appVersion)
        put(H_SDK_VERSION, sdkVersion)
        put(H_LANGUAGE, language)
        return out
    }

    /** Keeps printable ASCII only (HTTP header values must be ASCII-safe), trimmed. */
    fun asciiSafe(value: String?): String {
        if (value == null) return ""
        val sb = StringBuilder(value.length)
        for (c in value) if (c in ' '..'~') sb.append(c)
        return sb.toString().trim()
    }

    /** `versionName` of the host app, or null. Never throws. */
    fun appVersionName(context: Context): String? = try {
        val pm = context.packageManager
        val name = context.packageName
        if (pm == null || name.isNullOrEmpty()) {
            null
        } else {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(name, 0)
            }
            info?.versionName
        }
    } catch (t: Throwable) {
        Logger.w("Could not read app versionName", t)
        null
    }
}
