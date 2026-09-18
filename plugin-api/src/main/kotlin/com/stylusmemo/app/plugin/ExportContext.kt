package com.stylusmemo.app.plugin

import com.stylusmemo.app.model.Note

/**
 * Everything a plugin needs to do its job for the export that is being run.
 *
 * The host builds one [ExportContext] per export operation and hands it to the
 * selected [NoteExporterPlugin].
 *
 * @property note       the note being exported (pages, boxes, backgrounds, ...)
 * @property config     plugin configuration injected by the host; plugin keys are
 *                      namespaced with the plugin id and come from the app's
 *                      settings (e.g. `export-ai-markdown.api_key`). Empty for
 *                      plugins without user configuration.
 * @property rasterizer lets a plugin request rendered page images (for OCR /
 *                      AI transcription / previews). Implementations are free to
 *                      return null for pages they do not need.
 */
class ExportContext(
    val note: Note,
    val config: Map<String, String> = emptyMap(),
    val rasterizer: PageRasterizer? = null,
)