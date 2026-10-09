package app.stringcast.sdk.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.text.Spanned
import androidx.core.text.HtmlCompat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges Android resource ids / names and OTA values: id → entry-name cache, name → id cache,
 * formatting, plural selection and draft-mode reporting for resource-based lookups.
 */
internal class ResourceLookup(private val engine: Engine) {

    /** Resource id → "type/name" (or [NONE] when the id isn't a string-ish resource). */
    private val idNames = ConcurrentHashMap<Int, String>()

    /** "type/name" → resource id (0 when the app has no such resource). */
    private val nameIds = ConcurrentHashMap<String, Int>()

    private val appPackage: String = engine.app.packageName

    @Volatile
    private var baseResCache: Pair<String, Resources>? = null

    // ---------------------------------------------------------------------------------------
    // id <-> name
    // ---------------------------------------------------------------------------------------

    /** Returns the entry name if [id] is an app `string`/`plurals`/`array` resource of [type]. */
    fun nameFor(res: Resources, id: Int, type: String): String? {
        if (id == 0 || (id ushr 24) == 0) return null
        val cached = idNames[id] ?: run {
            val v = try {
                val t = res.getResourceTypeName(id)
                if (t == "string" || t == "plurals" || t == "array") "$t/${res.getResourceEntryName(id)}" else NONE
            } catch (e: Resources.NotFoundException) {
                NONE
            }
            idNames.putIfAbsent(id, v) ?: v
        }
        if (cached == NONE) return null
        val slash = cached.indexOf('/')
        return if (cached.regionMatches(0, type, 0, slash) && slash == type.length) cached.substring(slash + 1) else null
    }

    /** Resolves a key to the app's compiled resource id of [type], or 0. */
    @SuppressLint("DiscouragedApi") // key-based lookup is the point of StringCast.getString(key)
    fun idFor(name: String, type: String): Int {
        val k = "$type/$name"
        nameIds[k]?.let { return it }
        val id = try {
            engine.app.resources.getIdentifier(name, type, appPackage)
        } catch (t: Throwable) {
            0
        }
        nameIds[k] = id
        return id
    }

    // ---------------------------------------------------------------------------------------
    // OTA values for resource ids
    // ---------------------------------------------------------------------------------------

    /** OTA text for a `string` resource id (raw, not formatted, may contain HTML), or null. */
    fun otaText(res: Resources, id: Int): String? {
        val name = nameFor(res, id, "string") ?: return null
        return when (val v = engine.lookup(name)) {
            is Value.Text -> v.value
            is Value.Plural -> v.other
            else -> {
                maybeReport(res, id, name, "string")
                null
            }
        }
    }

    fun otaPlural(res: Resources, id: Int, quantity: Int): String? {
        val name = nameFor(res, id, "plurals") ?: return null
        return when (val v = engine.lookup(name)) {
            is Value.Plural -> PluralFallback.pick(v.forms, Plurals.category(engine.language, quantity))
            is Value.Text -> v.value
            else -> {
                maybeReport(res, id, name, "plurals")
                null
            }
        }
    }

    fun otaArray(res: Resources, id: Int): List<String>? {
        val name = nameFor(res, id, "array") ?: return null
        return when (val v = engine.lookup(name)) {
            is Value.StringArray -> v.items
            else -> {
                maybeReport(res, id, name, "array")
                null
            }
        }
    }

    /**
     * Converts an OTA string to the CharSequence `getText` should return: if the compiled resource
     * is styled (`<b>` etc. in strings.xml) and the OTA value carries tags, they are rendered as
     * spans; otherwise the value is returned literally (escaped `&lt;b>` strings stay literal).
     */
    fun styled(res: Resources, id: Int, value: String, original: () -> CharSequence?): CharSequence {
        if (!TAG.containsMatchIn(value)) return value
        val orig = try {
            original()
        } catch (t: Throwable) {
            null
        }
        if (orig !is Spanned) return value
        return HtmlCompat.fromHtml(value.replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_LEGACY).trimEnd()
    }

    fun format(value: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return value
        return try {
            String.format(engine.locale(), value, *args)
        } catch (t: Throwable) {
            Logger.w("Format failed for \"${value.take(80)}\"", t)
            value
        }
    }

    // ---------------------------------------------------------------------------------------
    // Draft mode
    // ---------------------------------------------------------------------------------------

    private fun maybeReport(res: Resources, id: Int, name: String, type: String) {
        val reporter = engine.missing ?: return
        if (!engine.isMissingFromBase(name)) return
        if (!isAppOwned(res, id, name, type)) return
        reporter.report(name) { valueForResource(id, type) }
    }

    /** Excludes framework and (as far as we can tell) library resources from missing reports. */
    private fun isAppOwned(res: Resources, id: Int, name: String, type: String): Boolean {
        val pkg = try {
            res.getResourcePackageName(id)
        } catch (t: Throwable) {
            return false
        }
        if (pkg == "android") return false
        val own = LocalStrings.appResourceNames(engine.app, type)
        if (own != null) return name in own
        return LIBRARY_PREFIXES.none { name.startsWith(it) }
    }

    /** Reads a compiled value in the project's base language (used for `/missing` payloads). */
    fun valueForResource(id: Int, type: String): Value? = try {
        val res = baseResources()
        when (type) {
            "string" -> Value.Text(res.getText(id).toString())
            "plurals" -> {
                val forms = LinkedHashMap<String, String>()
                for ((category, n) in Plurals.samples(baseLanguage())) {
                    forms[category] = res.getQuantityText(id, n).toString()
                }
                if ("other" !in forms) forms["other"] = res.getQuantityText(id, 100).toString()
                Value.Plural(forms)
            }
            "array" -> {
                val arr = res.getStringArray(id)
                if (arr.any { it == null }) null else Value.StringArray(arr.toList())
            }
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    fun baseLanguage(): String = engine.state.manifest?.baseLanguage?.ifEmpty { null } ?: "en"

    /** App resources configured for the project's base language (i.e. normally `values/`). */
    @SuppressLint("AppBundleLocaleChanges") // only reads the default `values/` strings
    fun baseResources(): Resources {
        val lang = baseLanguage()
        baseResCache?.let { if (it.first == lang) return it.second }
        val app: Context = engine.app
        val cfg = Configuration(app.resources.configuration)
        val locale = Locale.forLanguageTag(lang)
        if (Build.VERSION.SDK_INT >= 24) cfg.setLocales(LocaleList(locale)) else @Suppress("DEPRECATION") cfg.setLocale(locale)
        val res = app.createConfigurationContext(cfg).resources.let { (it as? StringCastResources)?.original ?: it }
        baseResCache = lang to res
        return res
    }

    companion object {
        private const val NONE = "\u0000"
        private val TAG = Regex("<\\s*/?\\s*(b|i|u|em|strong|small|big|sup|sub|strike|s|tt|font|a|br|span)\\b", RegexOption.IGNORE_CASE)
        private val LIBRARY_PREFIXES = listOf(
            "abc_", "mtrl_", "material_", "m3_", "androidx_", "fab_", "bottomsheet_", "appbar_", "character_counter_",
            "clear_text_", "error_icon_", "exposed_dropdown_", "hide_bottom_view_", "icon_content_description",
            "item_view_role_", "password_toggle_", "path_password_", "search_menu_title", "status_bar_notification_",
            "call_notification_", "side_sheet_", "searchbar_", "searchview_", "common_google_", "fcm_",
        )
    }
}
