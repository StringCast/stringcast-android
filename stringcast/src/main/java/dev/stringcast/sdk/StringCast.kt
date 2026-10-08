package dev.stringcast.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.view.View
import dev.stringcast.sdk.internal.Engine
import dev.stringcast.sdk.internal.LocalStrings
import dev.stringcast.sdk.internal.Logger
import dev.stringcast.sdk.internal.MissingKeyReporter
import dev.stringcast.sdk.internal.PluralFallback
import dev.stringcast.sdk.internal.Plurals
import dev.stringcast.sdk.internal.StringCastContextWrapper
import dev.stringcast.sdk.internal.Value
import dev.stringcast.sdk.internal.ViewLocalizer
import kotlinx.coroutines.launch

/**
 * StringCast over-the-air localization.
 *
 * ```
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         StringCast.init(this, StringCastConfig(projectId = "p_…", sdkKey = "pk_…", baseUrl = "https://…"))
 *     }
 * }
 *
 * class MainActivity : Activity() {
 *     override fun attachBaseContext(newBase: Context) = super.attachBaseContext(StringCast.wrap(newBase))
 * }
 * ```
 *
 * Every method is safe to call from any thread, before [init], and never throws.
 */
public object StringCast {

    @SuppressLint("StaticFieldLeak") // Engine only holds the application context
    @Volatile
    private var engine: Engine? = null

    @JvmStatic
    internal fun engineOrNull(): Engine? = engine

    /**
     * Initialises the SDK. Loads the disk cache synchronously (a few small file reads) so the first
     * screen is already localized, then checks for a new release in the background.
     * Calling it again is a no-op.
     */
    @JvmStatic
    public fun init(context: Context, config: StringCastConfig) {
        synchronized(this) {
            if (engine != null) {
                Logger.w("StringCast.init called twice; ignoring")
                return
            }
            try {
                Logger.verbose = config.logging
                val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
                val e = Engine(context, config, config.draftMode ?: debuggable)
                e.loadCache()
                engine = e
                e.start()
                Logger.i("Initialised project=${config.projectId} draftMode=${e.draftMode} language=${e.language}")
            } catch (t: Throwable) {
                Logger.w("StringCast.init failed; falling back to compiled strings", t)
            }
        }
    }

    /** True once [init] succeeded. */
    @JvmStatic
    public val isInitialized: Boolean get() = engine != null

    /** True if draft mode is active (missing-key reporting and [uploadLocalStrings]). */
    @JvmStatic
    public val isDraftMode: Boolean get() = engine?.draftMode == true

    /** The resolved language in use (e.g. `es`), or null before [init]. */
    @JvmStatic
    public val currentLanguage: String? get() = engine?.language?.ifEmpty { null }

    /** Version of the release in use (0 = none). */
    @JvmStatic
    public val currentVersion: Int get() = engine?.state?.manifest?.version ?: 0

    // -------------------------------------------------------------------------------------------
    // Context wrapping
    // -------------------------------------------------------------------------------------------

    /**
     * Wraps [base] so `getResources()` serves OTA strings (getString/getText/getQuantityString/
     * getStringArray/…) and views inflated from XML get OTA `text`/`hint`/`contentDescription`.
     * Use from `Activity.attachBaseContext` (and optionally `Application.attachBaseContext`).
     * Safe to call before [init]: lookups simply fall back to the compiled resources until then.
     */
    @JvmStatic
    public fun wrap(base: Context): Context =
        if (base is StringCastContextWrapper) base else try {
            StringCastContextWrapper(base)
        } catch (t: Throwable) {
            Logger.w("wrap failed", t)
            base
        }

    /**
     * Re-applies strings to an existing view hierarchy: views inflated through a wrapped context
     * (their resource ids are remembered) and TextViews with `android:tag="stringcast:<key>"`.
     * Useful in an update listener instead of `recreate()`.
     */
    @JvmStatic
    public fun localizeViewTree(root: View) {
        ViewLocalizer.localizeTree(root)
    }

    // -------------------------------------------------------------------------------------------
    // Lookups by key
    // -------------------------------------------------------------------------------------------

    /**
     * String for [key]: resolved-language bundle → base bundle → compiled `R.string.<key>` → [key].
     * With [args] the value is formatted like `Resources.getString(id, args)`.
     */
    @JvmStatic
    public fun getString(key: String, vararg args: Any?): String {
        val e = engine ?: return compiledString(null, key, args) ?: key
        return try {
            when (val v = e.lookup(key)) {
                is Value.Text -> e.resources.format(v.value, args)
                is Value.Plural -> e.resources.format(v.other ?: key, args)
                else -> {
                    reportByKey(e, key, "string")
                    compiledString(e, key, args) ?: key
                }
            }
        } catch (t: Throwable) {
            Logger.w("getString($key) failed", t)
            key
        }
    }

    /**
     * Plural for [key] and [quantity], with the category chosen by the CLDR rules of the resolved
     * language. Like `Resources.getQuantityString`, the value is only formatted when [args] are
     * passed (usually `getQuantityString("items", n, n)`).
     */
    @JvmStatic
    public fun getQuantityString(key: String, quantity: Int, vararg args: Any?): String {
        val e = engine ?: return compiledPlural(null, key, quantity, args) ?: key
        return try {
            when (val v = e.lookup(key)) {
                is Value.Plural -> {
                    val form = PluralFallback.pick(v.forms, Plurals.category(e.language, quantity)) ?: key
                    e.resources.format(form, args)
                }
                is Value.Text -> e.resources.format(v.value, args)
                else -> {
                    reportByKey(e, key, "plurals")
                    compiledPlural(e, key, quantity, args) ?: key
                }
            }
        } catch (t: Throwable) {
            Logger.w("getQuantityString($key) failed", t)
            key
        }
    }

    /** String array for [key]; empty if neither a bundle nor the app has it. */
    @JvmStatic
    public fun getStringArray(key: String): Array<String> {
        val e = engine ?: return compiledArray(null, key) ?: emptyArray()
        return try {
            when (val v = e.lookup(key)) {
                is Value.StringArray -> v.items.toTypedArray()
                else -> {
                    reportByKey(e, key, "array")
                    compiledArray(e, key) ?: emptyArray()
                }
            }
        } catch (t: Throwable) {
            Logger.w("getStringArray($key) failed", t)
            emptyArray()
        }
    }

    // -------------------------------------------------------------------------------------------
    // Control
    // -------------------------------------------------------------------------------------------

    /**
     * Checks for a new release in the background. Without [force] this is skipped if the last
     * check is more recent than `refreshIntervalMs`.
     */
    @JvmStatic
    @JvmOverloads
    public fun refresh(force: Boolean = false) {
        try {
            engine?.refresh(force)
        } catch (t: Throwable) {
            Logger.w("refresh failed", t)
        }
    }

    /**
     * Forces a language (`es`, `pt-BR`) or, with null, returns to the device languages (or the
     * config's `languageOverride`). Persisted across launches. Listeners are notified when the
     * new language's strings are in place — recreate activities from there.
     */
    @JvmStatic
    public fun setLanguage(code: String?) {
        try {
            engine?.setLanguage(code)
        } catch (t: Throwable) {
            Logger.w("setLanguage failed", t)
        }
    }

    /** The language last set with [setLanguage] (persisted across launches), or null. */
    @JvmStatic
    public val languageOverride: String? get() = engine?.languageOverride

    /** Project languages from the current manifest (empty until one has been fetched/cached). */
    @JvmStatic
    public val availableLanguages: List<String> get() = engine?.state?.manifest?.languageCodes.orEmpty()

    /**
     * [listener] is called on the main thread whenever a new release or language has been
     * applied. A typical reaction is `activity.recreate()` or [localizeViewTree].
     */
    @JvmStatic
    public fun addUpdateListener(listener: StringCastUpdateListener) {
        engine?.addListener(listener) ?: Logger.w("addUpdateListener before init; ignored")
    }

    @JvmStatic
    public fun removeUpdateListener(listener: StringCastUpdateListener) {
        engine?.removeListener(listener)
    }

    /**
     * Draft mode only: uploads every `R.string`, `R.plurals` and `R.array` entry of the app (in
     * the project's base language, i.e. normally `values/`) to `/missing`, in batches of ≤500.
     * The server only creates keys that don't exist yet; it never overwrites.
     *
     * @param rClass the app's `R` class (e.g. `R::class.java`). Optional: by default it is looked
     *   up from the package name (with `.debug`-style suffixes stripped).
     * @param callback optional, invoked on the main thread with the outcome.
     */
    @JvmStatic
    @JvmOverloads
    public fun uploadLocalStrings(rClass: Class<*>? = null, callback: ((UploadResult) -> Unit)? = null) {
        val e = engine
        val main = Handler(Looper.getMainLooper())
        fun done(r: UploadResult) {
            if (r.error != null) Logger.w("uploadLocalStrings: ${r.error}") else Logger.i("uploadLocalStrings: $r")
            callback?.let { cb -> main.post { runCatching { cb(r) } } }
        }
        if (e == null) return done(UploadResult(0, 0, 0, "StringCast is not initialised"))
        if (!e.draftMode) return done(UploadResult(0, 0, 0, "uploadLocalStrings requires draft mode"))
        e.scope.launch {
            try {
                if (e.awaitManifest() == null) {
                    done(UploadResult(0, 0, 0, "Could not reach the StringCast API"))
                    return@launch
                }
                val entries = ArrayList<Pair<String, Value>>()
                for (type in listOf("string", "plurals", "array")) {
                    for ((name, id) in LocalStrings.entries(e.app, type, rClass)) {
                        e.resources.valueForResource(id, type)?.let { entries += name to it }
                    }
                }
                if (entries.isEmpty()) {
                    done(UploadResult(0, 0, 0, "No R\$string entries found; pass your R class explicitly"))
                    return@launch
                }
                var created = 0
                var ignored = 0
                var error: String? = null
                val language = e.resources.baseLanguage()
                for (chunk in entries.chunked(MissingKeyReporter.MAX_BATCH)) {
                    val resp = e.postMissing(language, chunk)
                    if (resp.isSuccess) {
                        val o = runCatching { org.json.JSONObject(resp.text()) }.getOrNull()
                        created += o?.optInt("created") ?: 0
                        ignored += o?.optInt("ignored") ?: 0
                    } else {
                        error = "HTTP ${resp.code}: ${resp.text().take(300)}"
                    }
                }
                done(UploadResult(entries.size, created, ignored, error))
            } catch (t: Throwable) {
                done(UploadResult(0, 0, 0, t.toString()))
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------------

    private fun reportByKey(e: Engine, key: String, type: String) {
        val reporter = e.missing ?: return
        if (!e.isMissingFromBase(key)) return
        reporter.report(key) {
            val id = e.resources.idFor(key, type)
            if (id != 0) e.resources.valueForResource(id, type) else null
        }
    }

    private fun compiledString(e: Engine?, key: String, args: Array<out Any?>): String? {
        val eng = e ?: return null
        val id = eng.resources.idFor(key, "string")
        if (id == 0) return null
        return try {
            val res = eng.compiledResources()
            if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
        } catch (t: Throwable) {
            null
        }
    }

    private fun compiledPlural(e: Engine?, key: String, quantity: Int, args: Array<out Any?>): String? {
        val eng = e ?: return null
        val id = eng.resources.idFor(key, "plurals")
        if (id == 0) return null
        return try {
            if (args.isEmpty()) {
                eng.compiledResources().getQuantityString(id, quantity)
            } else {
                eng.compiledResources().getQuantityString(id, quantity, *args)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun compiledArray(e: Engine?, key: String): Array<String>? {
        val eng = e ?: return null
        val id = eng.resources.idFor(key, "array")
        if (id == 0) return null
        return try {
            eng.compiledResources().getStringArray(id)
        } catch (t: Throwable) {
            null
        }
    }

}
