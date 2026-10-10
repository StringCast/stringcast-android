package app.stringcast.sdk.internal

import android.content.Context
import java.lang.reflect.Modifier

/**
 * Reflection over the app's generated `R$string`, `R$plurals` and `R$array` classes: the set of
 * keys the app *owns* (draft-mode upload and missing-key reports).
 *
 * The R classes come from `StringCastConfig.rClasses` (one per module that has strings — with
 * AGP 8's non-transitive R classes each module has its own `R`), or, when none are configured,
 * from discovery: `<applicationId>.R` and its parent packages (so `com.acme.app.qa` finds
 * `com.acme.app.R`) plus every module `R` found in the app's dex files under the app's package
 * root (`com.acme.feature.auth.R`, …; library R classes such as `androidx.*` are outside it). The result is then passed through [KeyFilter] so library strings merged into
 * a transitive `R` are dropped.
 */
internal object LocalStrings {

    val TYPES: List<String> = listOf("string", "plurals", "array")

    /** One owned resource: `R.<type>.<name>` with its resource [id]. */
    data class Entry(val type: String, val name: String, val id: Int)

    /** Candidate packages for the app's R class: applicationId and its parents (strips `.debug` etc.). */
    fun candidatePackages(packageName: String?, applicationClassName: String?): List<String> {
        val out = LinkedHashSet<String>()
        var pkg = packageName.orEmpty()
        while (pkg.count { it == '.' } >= 1) {
            out += pkg
            pkg = pkg.substringBeforeLast('.')
        }
        applicationClassName?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }?.let { out += it }
        return out.toList()
    }

    fun candidatePackages(context: Context): List<String> =
        candidatePackages(context.packageName, context.applicationInfo?.className)

    /** The first `<pkg>.R` among [packages] that exists, as a single-element list (or empty). */
    fun discoverRClasses(packages: List<String>, loader: ClassLoader?): List<Class<*>> {
        for (pkg in packages) {
            val r = loadClass("$pkg.R", loader)
            // Accept the R class only if it actually has string-ish resources.
            if (r != null && TYPES.any { nested(r, it) != null }) return listOf(r)
        }
        return emptyList()
    }

    /** First two segments of the application id (`com.acme.app.qa` → `com.acme`): the app's package root. */
    fun packageRoot(packageName: String?): String? =
        packageName?.split('.')?.filter { it.isNotEmpty() }?.takeIf { it.size >= 2 }?.take(2)?.joinToString(".")

    /** Outer `R` class names of the app's own modules among dex [classNames], e.g. `com.acme.feature.auth.R`. */
    fun moduleRClassNames(classNames: Sequence<String>, root: String): List<String> =
        classNames
            .filter { name -> name.startsWith("$root.") && TYPES.any { name.endsWith(".R\$$it") } }
            .map { it.substringBeforeLast('$') }
            .distinct()
            .sorted()
            .toList()

    /**
     * Every module R class of the app, found by listing the installed APK's dex classes. Draft mode
     * only (debug builds, background thread). `DexFile` is deprecated but still the only way to list
     * an APK's classes; any failure just yields an empty list.
     */
    @Suppress("DEPRECATION")
    fun discoverModuleRClasses(context: Context): List<Class<*>> {
        val root = packageRoot(context.packageName) ?: return emptyList()
        val info = context.applicationInfo ?: return emptyList()
        val apks = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
        val names = ArrayList<String>()
        for (apk in apks) {
            try {
                val dex = dalvik.system.DexFile(apk)
                try {
                    val entries = dex.entries()
                    while (entries.hasMoreElements()) names += entries.nextElement()
                } finally {
                    dex.close()
                }
            } catch (t: Throwable) {
                Logger.w("Could not list classes of $apk for R discovery", t)
            }
        }
        return moduleRClassNames(names.asSequence(), root).mapNotNull { loadClass(it, context.classLoader) }
    }

    /** `R$<type>` nested in [rClass], or null. */
    fun nested(rClass: Class<*>, type: String): Class<*>? {
        try {
            rClass.declaredClasses.firstOrNull { it.simpleName == type }?.let { return it }
        } catch (_: Throwable) {
        }
        return loadClass("${rClass.name}\$$type", rClass.classLoader)
    }

    /**
     * Every static int field of `R$string`, `R$plurals` and `R$array` of each class in [rClasses],
     * de-duplicated by key name (the StringCast key namespace is flat; first occurrence wins).
     * [nameOf] maps (resource id, field name) to the real resource name — fields have `.` replaced
     * by `_`, so on a device this is `Resources.getResourceEntryName(id)`; null skips the field.
     */
    fun scan(rClasses: List<Class<*>>, nameOf: (id: Int, fieldName: String) -> String?): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        for (r in rClasses.distinct()) {
            for (type in TYPES) {
                val cls = nested(r, type) ?: continue
                val fields = try {
                    cls.declaredFields
                } catch (_: Throwable) {
                    continue
                }
                for (f in fields) {
                    if (!Modifier.isStatic(f.modifiers) || f.type != Int::class.javaPrimitiveType) continue
                    try {
                        f.isAccessible = true
                        val id = f.getInt(null)
                        val name = nameOf(id, f.name) ?: continue
                        if (seen.add(name)) out += Entry(type, name, id)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return out
    }

    /**
     * The owned entries: [explicit] R classes (config `rClasses` + an optional extra one), or the
     * [discover]ed app R class when none are given; minus everything [filter] excludes.
     */
    fun owned(
        explicit: List<Class<*>>,
        discover: () -> List<Class<*>>,
        filter: KeyFilter,
        nameOf: (id: Int, fieldName: String) -> String?,
    ): List<Entry> {
        val classes = explicit.ifEmpty { discover() }
        return scan(classes, nameOf).filterNot { filter.isExcluded(it.name) }
    }

    /** Resource-name resolver backed by the app's resources (null for ids it doesn't know). */
    fun resourceNameResolver(context: Context): (Int, String) -> String? {
        val res = context.resources
        return { id, _ ->
            try {
                res.getResourceEntryName(id)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun loadClass(name: String, loader: ClassLoader?): Class<*>? = try {
        Class.forName(name, false, loader ?: LocalStrings::class.java.classLoader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }
}
