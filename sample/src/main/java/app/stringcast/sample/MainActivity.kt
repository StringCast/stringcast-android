package app.stringcast.sample

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.stringcast.sdk.StringCast
import app.stringcast.sdk.StringCastUpdateListener

class MainActivity : Activity() {

    private lateinit var items: TextView
    private lateinit var status: TextView
    private var count = 1

    // Recreate the activity whenever a new release or language is applied.
    // (StringCast.localizeViewTree(window.decorView) is a lighter alternative.)
    private val onUpdate = StringCastUpdateListener { recreate() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(StringCast.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        items = findViewById(R.id.items)
        status = findViewById(R.id.status)

        findViewById<SeekBar>(R.id.count).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                count = progress
                render()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val group = findViewById<RadioGroup>(R.id.languages)
        group.check(
            when (StringCast.languageOverride) {
                "en" -> R.id.lang_en
                "es" -> R.id.lang_es
                "fr" -> R.id.lang_fr
                else -> R.id.lang_system
            },
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            val code = when (checkedId) {
                R.id.lang_en -> "en"
                R.id.lang_es -> "es"
                R.id.lang_fr -> "fr"
                else -> null
            }
            StringCast.setLanguage(code)
        }

        findViewById<Button>(R.id.refresh).setOnClickListener {
            StringCast.refresh(force = true)
        }
        findViewById<Button>(R.id.refresh).setOnLongClickListener {
            // Draft mode only: push every R.string / R.plurals / R.array entry to the project.
            StringCast.uploadLocalStrings(R::class.java) { r ->
                Toast.makeText(this, r.error ?: "Uploaded ${r.total}: ${r.created} created, ${r.ignored} existing", Toast.LENGTH_LONG).show()
            }
            true
        }

        render()
    }

    override fun onStart() {
        super.onStart()
        StringCast.addUpdateListener(onUpdate)
    }

    override fun onStop() {
        StringCast.removeUpdateListener(onUpdate)
        super.onStop()
    }

    private fun render() {
        // Resources-based lookups go through the wrapped context…
        items.text = resources.getQuantityString(R.plurals.items_count, count, count)
        // …and key-based lookups are available anywhere.
        status.text = StringCast.getString("status_format", StringCast.currentVersion, StringCast.currentLanguage ?: "-")
    }
}
