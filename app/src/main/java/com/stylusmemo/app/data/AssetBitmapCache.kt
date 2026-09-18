package com.stylusmemo.app.data

import android.graphics.Bitmap

/**
 * Process-wide LRU cache of decoded asset bitmaps (imported images / rendered PDF pages), keyed by
 * "noteId:assetName". Keeping it across notes means reopening a note does not re-decode its PNGs.
 *
 * Bounded by an approximate byte budget so a few large PDF pages cannot exhaust the heap. Bitmaps
 * evicted from the cache are simply dropped (not recycled) because views may still be drawing them;
 * the GC reclaims them once unreferenced.
 */
class AssetBitmapCache(private val maxBytes: Long = 80L * 1024 * 1024) {

    private val entries = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {}

    private var bytes = 0L

    @Synchronized
    fun get(key: String): Bitmap? = entries[key]

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        val size = bitmap.allocationByteCount.toLong()
        if (size > maxBytes) return
        entries.put(key, bitmap)?.let { bytes -= it.allocationByteCount }
        bytes += size
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            iterator.remove()
            bytes -= entry.value.allocationByteCount
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0L
    }
}
