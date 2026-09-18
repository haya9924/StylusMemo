package com.stylusmemo.app.plugin

/**
 * Target for plugin output.
 *
 * The host app provides a sink that writes bytes somewhere the user can reach
 * (e.g. the app's exports folder, Documents via SAF, or a command line file).
 * Plugins only ever deal with bytes and file names, never with platform
 * storage APIs, which keeps the same plugin runnable in the app and on the CLI.
 */
fun interface DataSink {
    /**
     * Writes [bytes] as [fileName]. Returns true on success.
     * [fileName] may contain sub-directories (e.g. `"notes/out.md"`).
     */
    suspend fun write(fileName: String, bytes: ByteArray): Boolean
}