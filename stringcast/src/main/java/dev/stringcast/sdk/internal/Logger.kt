package dev.stringcast.sdk.internal

import android.util.Log

internal object Logger {
    private const val TAG = "StringCast"

    @Volatile
    var verbose: Boolean = false

    fun d(msg: String) {
        if (verbose) runCatching { Log.d(TAG, msg) }
    }

    fun i(msg: String) {
        if (verbose) runCatching { Log.i(TAG, msg) }
    }

    /** Warnings are always logged: they indicate a swallowed failure. */
    fun w(msg: String, t: Throwable? = null) {
        runCatching { if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg) }
    }
}
