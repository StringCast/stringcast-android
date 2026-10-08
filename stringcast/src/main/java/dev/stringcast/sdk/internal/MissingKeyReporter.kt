package dev.stringcast.sdk.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Draft-mode reporting of keys the app requests that the base bundle lacks (contract §6.6):
 * de-duplicated per process, debounced ~5 s, POSTed in background batches of at most 500.
 * Default values are computed lazily on the background thread.
 */
internal class MissingKeyReporter(private val engine: Engine) {

    private val seen: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pending = ConcurrentHashMap<String, () -> Value?>()
    private val scheduled = AtomicBoolean(false)

    fun report(key: String, defaultValue: () -> Value?) {
        if (!seen.add(key)) return
        pending[key] = defaultValue
        schedule()
    }

    fun schedule() {
        if (pending.isEmpty()) return
        if (!scheduled.compareAndSet(false, true)) return
        engine.scope.launch {
            delay(DEBOUNCE_MS)
            scheduled.set(false)
            flush()
        }
    }

    private fun flush() {
        if (engine.state.manifest == null) return // language unknown yet; re-scheduled after the next manifest
        val batch = ArrayList<Pair<String, Value>>()
        for (key in pending.keys.toList()) {
            val provider = pending.remove(key) ?: continue
            if (!engine.isMissingFromBase(key)) continue
            val value = try {
                provider()
            } catch (t: Throwable) {
                null
            }
            if (value == null) {
                Logger.d("Missing key '$key' has no local default; not reported")
                continue
            }
            batch += key to value
        }
        if (batch.isEmpty()) return
        val language = engine.resources.baseLanguage()
        for (chunk in batch.chunked(MAX_BATCH)) {
            try {
                val resp = engine.postMissing(language, chunk)
                if (resp.isSuccess) {
                    Logger.i("Reported ${chunk.size} missing key(s): ${resp.text().take(200)}")
                } else {
                    Logger.w("Missing-key report failed: HTTP ${resp.code} ${resp.text().take(200)}")
                    chunk.forEach { seen.remove(it.first) } // allow a later retry
                }
            } catch (t: Throwable) {
                Logger.w("Missing-key report failed", t)
                chunk.forEach { seen.remove(it.first) }
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 5_000L
        const val MAX_BATCH = 500
    }
}
