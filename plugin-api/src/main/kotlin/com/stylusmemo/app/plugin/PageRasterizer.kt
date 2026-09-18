package com.stylusmemo.app.plugin

/**
 * Renders a note page into PNG image bytes.
 *
 * This is the bridge between a plugin and the host's rendering pipeline
 * (e.g. the Android app's Ink renderer, or the desktop renderer). It lets
 * plugins that need the *picture* of a page (AI transcription, OCR, thumbnail
 * generators, ...) ask for it without depending on any rendering library.
 */
fun interface PageRasterizer {
    /**
     * Renders page [pageIndex] of the exported note as PNG bytes, scaled so its
     * larger side is at most about [maxDimPx] pixels. Returns null when the
     * page cannot be rendered.
     */
    suspend fun rasterize(pageIndex: Int, maxDimPx: Int): ByteArray?
}