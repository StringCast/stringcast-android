package app.stringcast.sdk.internal

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * `<filesDir>/stringcast/manifest.json` + `bundles/<lang>.json`. Every write goes to a temp file in
 * the same directory, is fsync'ed and then renamed over the target, so readers never observe a
 * partially written file.
 */
internal class DiskCache(root: File) {

    private val dir = File(root, "stringcast")
    private val bundlesDir = File(dir, "bundles")
    private val manifestFile = File(dir, "manifest.json")

    fun readManifestText(): String? = read(manifestFile)

    fun writeManifest(text: String) = atomicWrite(manifestFile, text.toByteArray(Charsets.UTF_8))

    fun readBundleText(language: String): String? = read(bundleFile(language))

    fun writeBundle(language: String, bytes: ByteArray) = atomicWrite(bundleFile(language), bytes)

    fun hasBundle(language: String): Boolean = bundleFile(language).isFile

    /** Removes cached bundles whose language is not in [keep]. */
    fun pruneBundles(keep: Collection<String>) {
        val keepNames = keep.map { fileName(it) }.toSet()
        bundlesDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".json") && f.name !in keepNames) f.delete()
        }
    }

    fun clear() {
        dir.deleteRecursively()
    }

    private fun bundleFile(language: String) = File(bundlesDir, fileName(language))

    private fun fileName(language: String): String {
        // Language tags are [A-Za-z0-9-]; anything else is stripped to keep paths safe.
        val safe = LanguageResolver.normalize(language).filter { it.isLetterOrDigit() || it == '-' }
        return "${safe.ifEmpty { "_" }}.json"
    }

    private fun read(file: File): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (e: IOException) {
        Logger.w("Failed to read ${file.name}", e)
        null
    }

    @Throws(IOException::class)
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("no parent dir")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create ${parent.path}")
        }
        val tmp = File(parent, "${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (!tmp.renameTo(target)) {
                // Some file systems refuse to rename over an existing file.
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("rename failed for ${target.name}")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
