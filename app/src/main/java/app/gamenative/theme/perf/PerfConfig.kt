package app.gamenative.theme.perf

import android.util.DisplayMetrics
import app.gamenative.BuildConfig
import java.util.Collections
import java.util.LinkedHashMap

/**
 * PerfConfig: Debug-only performance helpers for the Theme Engine.
 * - Premeasurement & layer-tree caching (bucketed by DPI)
 * - Clamp helpers for expensive effects (blur/shadow)
 * - Feature flags for enabling dev overlay and metric collection
 *
 * This module is safe to ship in release: everything is a no-op unless BuildConfig.DEBUG is true.
 */
object PerfConfig {

    // --- Flags (debug-only switches) ---
    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    @Volatile
    var overlayEnabled: Boolean = false

    @Volatile
    var collectMetrics: Boolean = true

    /** If true, the engine should premeasure templates and cache results per DPI bucket. */
    @Volatile
    var premeasureEnabled: Boolean = true

    // --- Effect clamps (keep conservative to reduce GPU cost) ---
    @Volatile
    var maxShadowRadiusPx: Float = 16f

    @Volatile
    var maxBlurRadiusPx: Float = 8f

    fun clampShadow(radiusPx: Float): Float = if (!enabled) radiusPx else radiusPx.coerceAtMost(maxShadowRadiusPx)
    fun clampBlur(radiusPx: Float): Float = if (!enabled) radiusPx else radiusPx.coerceAtMost(maxBlurRadiusPx)

    // --- DPI bucketing ---
    /** Map a raw dpi to a stable bucket to increase cache hit rates. */
    fun dpiBucketFor(dpi: Int): Int = when {
        dpi < 200 -> 160
        dpi < 280 -> 240
        dpi < 360 -> 320
        dpi < 440 -> 400 // common mid-high bucket
        dpi < 520 -> 480
        dpi < 640 -> 560
        else -> 640
    }

    fun dpiBucketFor(metrics: DisplayMetrics): Int = dpiBucketFor(metrics.densityDpi)

    // --- Lightweight LRU caches (thread-safe wrappers) ---
    data class TemplateKey(val templateId: String, val dpiBucket: Int)

    data class TemplateMeasure(
        val widthPx: Int,
        val heightPx: Int,
        val extras: Map<String, Any?> = emptyMap(),
    )

    /** Placeholder type for a prepared layer tree (renderer-owned). */
    interface LayerTree

    private class Lru<K, V>(private val maxSize: Int) : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    // Keep caches small to avoid memory pressure; values are tiny structs or renderer handles.
    private val tmCacheLock = Any()
    private val templateMeasureCache: MutableMap<TemplateKey, TemplateMeasure> = Collections.synchronizedMap(Lru(256))

    private val ltCacheLock = Any()
    private val layerTreeCache: MutableMap<TemplateKey, LayerTree> = Collections.synchronizedMap(Lru(128))

    // --- Public cache helpers ---

    fun getTemplateMeasure(key: TemplateKey): TemplateMeasure? {
        if (!enabled || !premeasureEnabled) return null
        return templateMeasureCache[key]
    }

    fun putTemplateMeasure(key: TemplateKey, value: TemplateMeasure) {
        if (!enabled || !premeasureEnabled) return
        synchronized(tmCacheLock) { templateMeasureCache[key] = value }
    }

    fun getOrPutTemplateMeasure(key: TemplateKey, compute: () -> TemplateMeasure): TemplateMeasure {
        if (!enabled || !premeasureEnabled) return compute()
        val existing = templateMeasureCache[key]
        if (existing != null) return existing
        val computed = compute()
        putTemplateMeasure(key, computed)
        return computed
    }

    fun getLayerTree(key: TemplateKey): LayerTree? {
        if (!enabled) return null
        return layerTreeCache[key]
    }

    fun putLayerTree(key: TemplateKey, value: LayerTree) {
        if (!enabled) return
        synchronized(ltCacheLock) { layerTreeCache[key] = value }
    }

    fun getOrBuildLayerTree(key: TemplateKey, build: () -> LayerTree): LayerTree {
        if (!enabled) return build()
        val existing = layerTreeCache[key]
        if (existing != null) return existing
        val built = build()
        putLayerTree(key, built)
        return built
    }

    fun clearCaches() {
        synchronized(tmCacheLock) { templateMeasureCache.clear() }
        synchronized(ltCacheLock) { layerTreeCache.clear() }
    }
}
