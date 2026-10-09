package app.stringcast.sdk.internal

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.xmlpull.v1.XmlPullParser

/**
 * LayoutInflater that observes every view created from XML (through the default creation path and
 * through any factory the app or AppCompat installs) and hands it to [ViewLocalizer].
 *
 * Views created by fully-qualified class name are only observed when a factory is active (ours is
 * installed lazily, see [inflate]); the platform's `createView` is final.
 *
 * It never changes which class gets instantiated: factories keep precedence, and for
 * fully-qualified names nobody else handles it uses the same `createView` call the platform uses.
 */
internal class StringCastLayoutInflater(
    original: LayoutInflater,
    newContext: Context,
    install: Boolean,
) : LayoutInflater(original, newContext) {

    init {
        if (install) {
            // The copy constructor copied any existing factory; wrap it so ours observes its views.
            val existing: Factory2? = original.factory2 ?: original.factory?.let(::adapt)
            if (existing != null) super.setFactory2(ObservingFactory(existing))
        }
    }

    /**
     * Our observing factory is installed lazily, right before the first inflation, and only if
     * nobody installed one: AppCompat & co. check `getFactory() == null` (final, can't be faked)
     * in `onCreate` and would otherwise refuse to install theirs. Factories they install go
     * through [setFactory2] and get wrapped.
     */
    override fun inflate(parser: XmlPullParser, root: ViewGroup?, attachToRoot: Boolean): View {
        if (factory == null) {
            try {
                super.setFactory2(ObservingFactory(null))
            } catch (_: IllegalStateException) {
            }
        }
        return super.inflate(parser, root, attachToRoot)
    }

    override fun cloneInContext(newContext: Context): LayoutInflater =
        StringCastLayoutInflater(this, newContext, install = false)

    /** Same prefixes as the platform's PhoneLayoutInflater. */
    override fun onCreateView(name: String, attrs: AttributeSet): View? {
        for (prefix in PREFIXES) {
            try {
                val v = createView(name, prefix, attrs)
                if (v != null) return v
            } catch (_: ClassNotFoundException) {
            }
        }
        return super.onCreateView(name, attrs)
    }

    override fun onCreateView(parent: View?, name: String, attrs: AttributeSet): View? {
        val v = super.onCreateView(parent, name, attrs)
        if (v != null) ViewLocalizer.onInflated(v, attrs)
        return v
    }

    override fun setFactory(factory: Factory) {
        setFactory2(adapt(factory))
    }

    override fun setFactory2(factory: Factory2) {
        super.setFactory2(if (factory is ObservingFactory) factory else ObservingFactory(factory))
    }

    internal class ObservingFactory(val delegate: Factory2?) : Factory2 {
        override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
            var view = delegate?.onCreateView(parent, name, context, attrs)
            if (view == null && name.indexOf('.') > 0) view = createQualified(name, context, attrs)
            if (view != null) ViewLocalizer.onInflated(view, attrs)
            return view
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
            onCreateView(null, name, context, attrs)

        /**
         * Fully-qualified tags (custom views, androidx/material widgets) bypass onCreateView, so
         * they are created here with the same call the platform would make. On API < 29 this is
         * only done when it is guaranteed to use the right context; otherwise the platform
         * creates the view (it then just isn't auto-localized).
         */
        private fun createQualified(name: String, context: Context, attrs: AttributeSet): View? = try {
            val inflater = LayoutInflater.from(context)
            if (Build.VERSION.SDK_INT >= 29) {
                inflater.createView(context, name, null, attrs)
            } else if (inflater.context === context) {
                inflater.createView(name, null, attrs)
            } else {
                null
            }
        } catch (t: Throwable) {
            null // let the platform create it (and report any real error itself)
        }
    }

    companion object {
        private val PREFIXES = arrayOf("android.widget.", "android.webkit.", "android.app.")

        private fun adapt(f: Factory): Factory2 = f as? Factory2 ?: object : Factory2 {
            override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
                f.onCreateView(name, context, attrs)

            override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
                f.onCreateView(name, context, attrs)
        }
    }
}
