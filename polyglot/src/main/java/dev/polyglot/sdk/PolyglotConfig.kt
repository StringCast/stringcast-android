package dev.polyglot.sdk

/**
 * Configuration passed to [Polyglot.init].
 *
 * @property projectId Polyglot project id, e.g. `p_8f3k2j`.
 * @property sdkKey public SDK key (`pk_…`). Never put an upload key (`sk_…`) in an app.
 * @property baseUrl API origin without `/v1`, e.g. `https://api.example.com` or `http://10.0.2.2:8787`.
 * @property languageOverride forces a language (beats the device languages). [Polyglot.setLanguage]
 *   at runtime beats this value.
 * @property draftMode reports missing keys and enables [Polyglot.uploadLocalStrings].
 *   `null` (default) = enabled exactly when the app is debuggable (`android:debuggable`).
 * @property refreshIntervalMs minimum time between automatic manifest checks (default 15 min).
 *   [Polyglot.refresh] with `force = true` ignores it.
 * @property logging verbose logcat output (tag `Polyglot`). Failures are always logged as warnings.
 */
public data class PolyglotConfig @JvmOverloads constructor(
    val projectId: String,
    val sdkKey: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val languageOverride: String? = null,
    val draftMode: Boolean? = null,
    val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    val logging: Boolean = false,
) {
    public companion object {
        /** Hosted API origin. Override [baseUrl] for self-hosted / local backends. */
        public const val DEFAULT_BASE_URL: String = "https://api.polyglot.dev"
        public const val DEFAULT_REFRESH_INTERVAL_MS: Long = 15L * 60L * 1000L
    }
}

/** Passed to [PolyglotUpdateListener] when new strings have been applied. */
public data class PolyglotUpdate(
    /** Release version now in use (0 if nothing has been published). */
    val version: Int,
    /** Resolved language now in use, e.g. `es`. */
    val language: String,
)

/** Called on the main thread when a new release or language has been applied. */
public fun interface PolyglotUpdateListener {
    public fun onUpdate(update: PolyglotUpdate)
}

/** Result of [Polyglot.uploadLocalStrings]. */
public data class UploadResult(
    /** Number of local entries found (strings + plurals + arrays). */
    val total: Int,
    /** Keys created on the server. */
    val created: Int,
    /** Keys the server already had (never overwritten). */
    val ignored: Int,
    /** Non-null if (part of) the upload failed. */
    val error: String? = null,
)
