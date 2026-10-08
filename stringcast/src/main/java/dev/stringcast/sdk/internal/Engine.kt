package dev.stringcast.sdk.internal

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle as OsBundle
import android.os.Handler
import android.os.Looper
import android.os.LocaleList
import android.os.SystemClock
import dev.stringcast.sdk.BuildConfig
import dev.stringcast.sdk.StringCastConfig
import dev.stringcast.sdk.StringCastUpdate
import dev.stringcast.sdk.StringCastUpdateListener
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The SDK runtime (contract §6). One instance per process, created by `StringCast.init`.
 *
 * Threading: all mutable state lives in [state], an immutable snapshot swapped atomically through
 * a volatile field, so lookups from any thread are lock-free. Network/disk mutations are
 * serialised by [mutex] on a background dispatcher.
 */
internal class Engine(
    context: Context,
    val config: StringCastConfig,
    val draftMode: Boolean,
) {
    /** Immutable snapshot of everything lookups need. */
    data class State(
        val manifest: Manifest?,
        val language: String,
        val primary: Bundle?,
        val base: Bundle?,
    ) {
        val fingerprint: String
            get() = "${manifest?.version}|$language|${primary?.language}@${primary?.version}|${base?.language}@${base?.version}"
    }

    val app: Context = context.applicationContext ?: context
    private val cache = DiskCache(app.filesDir)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = Http(config.sdkKey, "stringcast-android/${BuildConfig.SDK_VERSION}")
    private val apiBase: String = normalizeBaseUrl(config.baseUrl)
    private val sdkBase = "$apiBase/v1/sdk/${config.projectId}"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<StringCastUpdateListener>()
    private val mutex = Mutex()

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            Logger.w("Background task failed", t)
        },
    )

    @Volatile
    var state: State = State(null, "", null, null)
        private set

    @Volatile
    private var runtimeOverride: String? = prefs.getString(KEY_OVERRIDE, null)

    @Volatile
    private var lastCheck = 0L

    val resources = ResourceLookup(this)
    val missing: MissingKeyReporter? = if (draftMode) MissingKeyReporter(this) else null

    // ---------------------------------------------------------------------------------------
    // Startup
    // ---------------------------------------------------------------------------------------

    /** Synchronous: loads the disk cache so the very first frame is already localized. */
    fun loadCache() {
        val manifest = cache.readManifestText()?.let { text ->
            runCatching { Json.parseManifest(text) }
                .onFailure { Logger.w("Cached manifest is corrupt; ignoring", it) }
                .getOrNull()
        }?.takeIf { it.projectId.isEmpty() || it.projectId == config.projectId }

        val language = resolveLanguage(manifest)
        val primary = readCachedBundle(language)
        val baseLang = manifest?.baseLanguage.orEmpty()
        val base = when {
            baseLang.isEmpty() -> null
            LanguageResolver.sameTag(baseLang, language) -> primary
            else -> readCachedBundle(baseLang)
        }
        state = State(manifest, language, primary, base)
        Logger.i("Loaded cache: v${manifest?.version ?: "-"} language=$language primary=${primary != null} base=${base != null}")
    }

    fun start() {
        val application = app as? Application
        application?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = refresh(force = false)
            override fun onActivityCreated(activity: Activity, savedInstanceState: OsBundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: OsBundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                scope.launch { mutex.withLock { ensureLanguage() } }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {}
        })
        // Initial background check; cheap thanks to If-None-Match.
        refresh(force = true)
    }

    // ---------------------------------------------------------------------------------------
    // Public operations
    // ---------------------------------------------------------------------------------------

    fun refresh(force: Boolean) {
        scope.launch { refreshNow(force) }
    }

    fun setLanguage(code: String?) {
        val normalized = code?.let(LanguageResolver::normalize)?.ifEmpty { null }
        runtimeOverride = normalized
        prefs.edit().apply { if (normalized == null) remove(KEY_OVERRIDE) else putString(KEY_OVERRIDE, normalized) }.apply()
        scope.launch { mutex.withLock { ensureLanguage() } }
    }

    fun addListener(l: StringCastUpdateListener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: StringCastUpdateListener) {
        listeners.remove(l)
    }

    fun postMissing(language: String, keys: List<Pair<String, Value>>): Http.Response =
        http.postJson("$sdkBase/missing", Json.missingPayload("android", language, keys))

    /** Makes sure a manifest is known (fetching it if needed). Returns it, or null when offline. */
    suspend fun awaitManifest(): Manifest? {
        state.manifest?.let { return it }
        refreshNow(force = true)
        return state.manifest
    }

    // ---------------------------------------------------------------------------------------
    // Lookup (lock-free)
    // ---------------------------------------------------------------------------------------

    fun lookup(key: String): Value? {
        val s = state
        return s.primary?.strings?.get(key) ?: s.base?.strings?.get(key)
    }

    /** True if draft-mode reporting is meaningful for [key] (a manifest is known and base lacks it). */
    fun isMissingFromBase(key: String): Boolean {
        val s = state
        val m = s.manifest ?: return false
        if (m.version == 0) return true
        val base = s.base ?: return false // base bundle not downloaded yet: don't guess
        return !base.strings.containsKey(key)
    }

    val language: String get() = state.language

    /** The app's compiled resources (unwrapped if the Application context was wrapped). */
    fun compiledResources(): android.content.res.Resources =
        app.resources.let { (it as? StringCastResources)?.original ?: it }

    /** The language set via setLanguage (persisted), if any. */
    val languageOverride: String? get() = runtimeOverride

    fun locale(): Locale {
        val lang = state.language
        return if (lang.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(lang)
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private fun effectiveOverride(): String? = runtimeOverride ?: config.languageOverride?.ifBlank { null }

    private fun resolveLanguage(manifest: Manifest?): String = LanguageResolver.resolve(
        override = effectiveOverride(),
        preferred = preferredLanguages(),
        available = manifest?.languageCodes.orEmpty(),
        baseLanguage = manifest?.baseLanguage,
    )

    private fun preferredLanguages(): List<String> = try {
        if (Build.VERSION.SDK_INT >= 24) {
            val list = LocaleList.getDefault()
            (0 until list.size()).map { list.get(it).toLanguageTag() }
        } else {
            listOf(Locale.getDefault().toLanguageTag())
        }
    } catch (t: Throwable) {
        listOf(Locale.getDefault().toLanguageTag())
    }

    private fun readCachedBundle(language: String): Bundle? {
        if (language.isEmpty()) return null
        val text = cache.readBundleText(language) ?: return null
        return runCatching { Json.parseBundle(text) }
            .onFailure { Logger.w("Cached bundle $language is corrupt; ignoring", it) }
            .getOrNull()
            ?.takeIf { it.projectId.isEmpty() || it.projectId == config.projectId }
            ?.let(::normalizeBundle)
    }

    private fun normalizeBundle(b: Bundle): Bundle = b.copy(
        strings = b.strings.mapValues { (_, v) ->
            when (v) {
                is Value.Text -> Value.Text(Placeholders.normalize(v.value))
                is Value.Plural -> Value.Plural(v.forms.mapValues { Placeholders.normalize(it.value) })
                is Value.StringArray -> Value.StringArray(v.items.map(Placeholders::normalize))
            }
        },
    )

    private suspend fun refreshNow(force: Boolean) {
        try {
            mutex.withLock { refreshLocked(force) }
        } catch (t: Throwable) {
            Logger.w("Refresh failed", t)
            lastCheck = 0L // retry on next opportunity
        }
    }

    private fun refreshLocked(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCheck != 0L && now - lastCheck < config.refreshIntervalMs) {
            ensureLanguage()
            return
        }
        lastCheck = now
        val current = state
        val etag = if (current.manifest != null) prefs.getString(KEY_ETAG, null) else null
        Logger.d("GET manifest (If-None-Match: $etag)")
        val resp = http.get("$sdkBase/manifest", ifNoneMatch = etag)
        val manifest: Manifest
        var manifestText: String? = null
        when {
            resp.code == 304 && current.manifest != null -> manifest = current.manifest
            resp.isSuccess -> {
                manifestText = resp.text()
                manifest = Json.parseManifest(manifestText)
                if (manifest.projectId.isNotEmpty() && manifest.projectId != config.projectId) {
                    Logger.w("Manifest projectId ${manifest.projectId} != ${config.projectId}; ignoring")
                    return
                }
            }
            else -> {
                Logger.w("Manifest request failed: HTTP ${resp.code} ${resp.text().take(300)}")
                lastCheck = 0L
                return
            }
        }
        applyManifest(manifest, manifestText, resp.etag)
    }

    private fun applyManifest(manifest: Manifest, manifestText: String?, etag: String?) {
        val previous = state
        val language = resolveLanguage(manifest)

        val primary = obtainBundle(manifest, language, previous)
        val base = if (LanguageResolver.sameTag(language, manifest.baseLanguage)) {
            primary
        } else {
            obtainBundle(manifest, manifest.baseLanguage, previous)
        }
        if (primary is Fetch.Failed || base is Fetch.Failed) {
            // Keep the previous snapshot and cache untouched; retry next time.
            lastCheck = 0L
            return
        }
        // Commit point: bundles are already on disk, now the manifest + etag.
        if (manifestText != null) {
            runCatching { cache.writeManifest(manifestText) }.onFailure { Logger.w("Failed to persist manifest", it) }
            prefs.edit().putString(KEY_ETAG, etag).apply()
            runCatching { cache.pruneBundles(manifest.languageCodes) }
        }
        publish(State(manifest, language, (primary as? Fetch.Ok)?.bundle, (base as? Fetch.Ok)?.bundle))
        missing?.schedule()
    }

    /** Applies a language change (override or device) using cache first, network second. */
    private fun ensureLanguage() {
        val s = state
        val manifest = s.manifest
        val language = resolveLanguage(manifest)
        val upToDate = s.primary?.let { manifest == null || it.version == manifest.version } ?: (manifest?.language(language) == null)
        if (LanguageResolver.sameTag(language, s.language) && upToDate) return

        // 1. Show whatever is cached immediately (possibly an older release).
        val cached = when {
            s.base != null && LanguageResolver.sameTag(s.base.language, language) -> s.base
            s.primary != null && LanguageResolver.sameTag(s.primary.language, language) -> s.primary
            else -> readCachedBundle(language)
        }
        publish(State(manifest, language, cached, s.base))

        // 2. Fetch the current release for that language if needed.
        if (manifest != null && manifest.language(language) != null && cached?.version != manifest.version) {
            val fetched = obtainBundle(manifest, language, state)
            if (fetched is Fetch.Ok) {
                val now = state
                if (LanguageResolver.sameTag(now.language, language)) publish(now.copy(primary = fetched.bundle))
            }
        }
    }

    private sealed class Fetch {
        class Ok(val bundle: Bundle) : Fetch()
        object NotInProject : Fetch()
        object Failed : Fetch()
    }

    private fun obtainBundle(manifest: Manifest, language: String, current: State): Fetch {
        val entry = manifest.language(language) ?: return Fetch.NotInProject
        // In memory?
        listOfNotNull(current.primary, current.base).firstOrNull {
            it.version == manifest.version && LanguageResolver.sameTag(it.language, entry.code)
        }?.let { return Fetch.Ok(it) }
        // On disk?
        readCachedBundle(entry.code)?.takeIf { it.version == manifest.version }?.let { return Fetch.Ok(it) }
        // Network.
        return try {
            val url = rewriteLocalhost(entry.url)
            Logger.d("GET bundle $url")
            val resp = http.get(url, sendKey = false)
            if (!resp.isSuccess || resp.body == null) {
                Logger.w("Bundle ${entry.code} download failed: HTTP ${resp.code}")
                return Fetch.Failed
            }
            val bytes = resp.body
            if (!Hashing.verify(entry.hash, bytes)) {
                Logger.w("Bundle ${entry.code} hash mismatch; rejecting")
                return Fetch.Failed
            }
            val bundle = Json.parseBundle(String(bytes, Charsets.UTF_8))
            if (bundle.version != manifest.version ||
                (bundle.language.isNotEmpty() && !LanguageResolver.sameTag(bundle.language, entry.code)) ||
                (bundle.projectId.isNotEmpty() && bundle.projectId != config.projectId)
            ) {
                Logger.w("Bundle ${entry.code} does not match manifest (v${bundle.version}/${bundle.language}/${bundle.projectId}); rejecting")
                return Fetch.Failed
            }
            cache.writeBundle(entry.code, bytes)
            Fetch.Ok(normalizeBundle(bundle))
        } catch (t: Throwable) {
            Logger.w("Bundle ${entry.code} download failed", t)
            Fetch.Failed
        }
    }

    private fun publish(newState: State) {
        val old = state
        state = newState
        if (old.fingerprint != newState.fingerprint) {
            Logger.i("Applied v${newState.manifest?.version ?: 0} language=${newState.language}")
            val update = StringCastUpdate(newState.manifest?.version ?: 0, newState.language)
            mainHandler.post {
                for (l in listeners) {
                    try {
                        l.onUpdate(update)
                    } catch (t: Throwable) {
                        Logger.w("Update listener threw", t)
                    }
                }
            }
        }
    }

    /**
     * Local dev servers may advertise `http://localhost:8787/...` bundle URLs; an emulator/device
     * can't reach those, so they are re-pointed at the configured [apiBase] host.
     */
    private fun rewriteLocalhost(url: String): String = try {
        val u = URL(url)
        val b = URL(apiBase)
        if ((u.host == "localhost" || u.host == "127.0.0.1") && u.host != b.host) {
            URL(b.protocol, b.host, b.port, u.file).toString()
        } else {
            url
        }
    } catch (t: Throwable) {
        url
    }

    companion object {
        private const val PREFS = "dev.stringcast.sdk"
        private const val KEY_ETAG = "manifest_etag"
        private const val KEY_OVERRIDE = "language_override"

        fun normalizeBaseUrl(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.endsWith("/v1")) u = u.removeSuffix("/v1")
            return u
        }
    }
}
