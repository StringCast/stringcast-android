package dev.polyglot.sdk.internal

import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.polyglot.sdk.Polyglot
import dev.polyglot.sdk.R

/**
 * Applies OTA strings to inflated views and re-applies them on demand ([localizeTree]).
 *
 * At inflation the string resource ids of `android:text`, `android:hint` and
 * `android:contentDescription` are remembered as view tags, so a later language/release change can
 * be applied to an existing hierarchy without recreating it.
 */
internal object ViewLocalizer {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    const val KEY_TAG_PREFIX = "polyglot:"

    fun onInflated(view: View, attrs: AttributeSet) {
        try {
            if (Polyglot.engineOrNull() == null) return
            if (view is TextView) {
                val textId = attrs.getAttributeResourceValue(ANDROID_NS, "text", 0)
                if (textId != 0) {
                    view.setTag(R.id.polyglot_text_res, textId)
                    textFor(view, textId)?.let { view.text = it }
                }
                val hintId = attrs.getAttributeResourceValue(ANDROID_NS, "hint", 0)
                if (hintId != 0) {
                    view.setTag(R.id.polyglot_hint_res, hintId)
                    textFor(view, hintId)?.let { view.hint = it }
                }
            }
            val cdId = attrs.getAttributeResourceValue(ANDROID_NS, "contentDescription", 0)
            if (cdId != 0) {
                view.setTag(R.id.polyglot_content_description_res, cdId)
                textFor(view, cdId)?.let { view.contentDescription = it }
            }
        } catch (t: Throwable) {
            Logger.w("Could not localize ${view.javaClass.simpleName}", t)
        }
    }

    /** OTA text for a string resource id, or null when there is no OTA value. */
    private fun textFor(view: View, id: Int): CharSequence? {
        val lookup = Polyglot.engineOrNull()?.resources ?: return null
        val res = view.resources
        val original = (res as? PolyglotResources)?.original ?: res
        val ota = lookup.otaText(original, id) ?: return null
        return lookup.styled(original, id, ota) { original.getText(id) }
    }

    /** Current text (OTA or compiled) for a remembered resource id. */
    private fun currentText(view: View, id: Int): CharSequence? =
        textFor(view, id) ?: runCatching {
            val res = view.resources
            ((res as? PolyglotResources)?.original ?: res).getText(id)
        }.getOrNull()

    fun localizeTree(root: View) {
        try {
            walk(root)
        } catch (t: Throwable) {
            Logger.w("localizeViewTree failed", t)
        }
    }

    private fun walk(view: View) {
        if (view is TextView) {
            (view.getTag(R.id.polyglot_text_res) as? Int)?.let { id -> currentText(view, id)?.let { view.text = it } }
            (view.getTag(R.id.polyglot_hint_res) as? Int)?.let { id -> currentText(view, id)?.let { view.hint = it } }
            // android:tag="polyglot:<key>" binds a TextView to a key directly.
            val tag = view.tag
            if (tag is String && tag.startsWith(KEY_TAG_PREFIX)) {
                view.text = Polyglot.getString(tag.substring(KEY_TAG_PREFIX.length))
            }
        }
        (view.getTag(R.id.polyglot_content_description_res) as? Int)?.let { id ->
            currentText(view, id)?.let { view.contentDescription = it }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
    }
}
