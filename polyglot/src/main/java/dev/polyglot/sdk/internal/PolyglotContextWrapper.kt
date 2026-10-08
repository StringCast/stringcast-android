package dev.polyglot.sdk.internal

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.view.LayoutInflater

/**
 * Context returned by `Polyglot.wrap`. Provides [PolyglotResources] and a LayoutInflater that
 * applies OTA strings to views inflated from XML (TextView text/hint and contentDescription),
 * because layout inflation reads `android:text` through TypedArray, bypassing Resources.getText.
 */
internal class PolyglotContextWrapper(base: Context) : ContextWrapper(base) {

    @Volatile
    private var wrappedFor: Resources? = null

    @Volatile
    private var wrapped: PolyglotResources? = null

    private var inflater: LayoutInflater? = null

    override fun getResources(): Resources {
        val base = super.getResources()
        if (base is PolyglotResources) return base
        val w = wrapped
        if (w != null && wrappedFor === base) return w
        return try {
            PolyglotResources(base).also {
                wrapped = it
                wrappedFor = base
            }
        } catch (t: Throwable) {
            Logger.w("Could not wrap Resources", t)
            base
        }
    }

    override fun getSystemService(name: String): Any? {
        if (Context.LAYOUT_INFLATER_SERVICE == name) {
            inflater?.let { return it }
            return try {
                val original = super.getSystemService(name) as LayoutInflater
                val i = if (original is PolyglotLayoutInflater) original else PolyglotLayoutInflater(original, this, install = true)
                inflater = i
                i
            } catch (t: Throwable) {
                Logger.w("Could not wrap LayoutInflater", t)
                super.getSystemService(name)
            }
        }
        return super.getSystemService(name)
    }

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
        val ctx = super.createConfigurationContext(overrideConfiguration)
        return if (ctx is PolyglotContextWrapper) ctx else PolyglotContextWrapper(ctx)
    }
}
