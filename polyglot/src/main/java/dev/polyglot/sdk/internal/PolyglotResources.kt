package dev.polyglot.sdk.internal

import android.content.res.Resources
import dev.polyglot.sdk.Polyglot

/**
 * Resources that answer string lookups from the OTA bundles first and fall back to the compiled
 * resources. Everything else (drawables, dimens, …) is the platform implementation.
 *
 * When Polyglot isn't initialised (or has no value for a key) every method behaves exactly like
 * the original Resources.
 */
@Suppress("DEPRECATION")
internal class PolyglotResources(val original: Resources) :
    Resources(original.assets, original.displayMetrics, original.configuration) {

    private fun lookup(): ResourceLookup? = Polyglot.engineOrNull()?.resources

    private fun otaText(id: Int): String? = try {
        lookup()?.otaText(original, id)
    } catch (t: Throwable) {
        Logger.w("OTA lookup failed", t)
        null
    }

    override fun getText(id: Int): CharSequence {
        val l = lookup()
        val ota = otaText(id) ?: return super.getText(id)
        return l?.styled(this, id, ota) { super.getText(id) } ?: ota
    }

    override fun getText(id: Int, def: CharSequence?): CharSequence? {
        val l = lookup()
        val ota = otaText(id) ?: return super.getText(id, def)
        return l?.styled(this, id, ota) { super.getText(id, def) } ?: ota
    }

    override fun getString(id: Int): String {
        val l = lookup()
        val ota = otaText(id) ?: return super.getString(id)
        // A styled compiled string returns plain text from getString(); mirror that.
        return l?.styled(this, id, ota) { super.getText(id) }?.toString() ?: ota
    }

    override fun getString(id: Int, vararg formatArgs: Any?): String {
        val l = lookup() ?: return super.getString(id, *formatArgs)
        val ota = otaText(id) ?: return super.getString(id, *formatArgs)
        val plain = l.styled(this, id, ota) { super.getText(id) }.toString()
        return l.format(plain, formatArgs)
    }

    override fun getQuantityText(id: Int, quantity: Int): CharSequence {
        val ota = otaPlural(id, quantity) ?: return super.getQuantityText(id, quantity)
        return ota
    }

    override fun getQuantityString(id: Int, quantity: Int): String {
        val ota = otaPlural(id, quantity) ?: return super.getQuantityString(id, quantity)
        return ota
    }

    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String {
        val l = lookup() ?: return super.getQuantityString(id, quantity, *formatArgs)
        val ota = otaPlural(id, quantity) ?: return super.getQuantityString(id, quantity, *formatArgs)
        return l.format(ota, formatArgs)
    }

    override fun getStringArray(id: Int): Array<String> {
        val ota = otaArray(id) ?: return super.getStringArray(id)
        return ota.toTypedArray()
    }

    override fun getTextArray(id: Int): Array<CharSequence> {
        val ota = otaArray(id) ?: return super.getTextArray(id)
        return ota.toTypedArray<CharSequence>()
    }

    private fun otaPlural(id: Int, quantity: Int): String? = try {
        lookup()?.otaPlural(original, id, quantity)
    } catch (t: Throwable) {
        Logger.w("OTA plural lookup failed", t)
        null
    }

    private fun otaArray(id: Int): List<String>? = try {
        lookup()?.otaArray(original, id)
    } catch (t: Throwable) {
        Logger.w("OTA array lookup failed", t)
        null
    }
}
