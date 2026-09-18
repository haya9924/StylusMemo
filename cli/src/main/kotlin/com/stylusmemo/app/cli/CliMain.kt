package com.stylusmemo.app.cli

import com.stylusmemo.app.model.Note
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.stream.ImageOutputStream

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    if (args.isEmpty()) return printUsage()
    when (args[0]) {
        "list" -> cmdList(args.drop(1))
        "export" -> cmdExport(args.drop(1))
        "sample" -> cmdSample(args.drop(1))
        "-h", "--help", "help" -> printUsage()
        else -> printUsage()
    }
}

private fun cmdList(args: List<String>) {
    val noteDir = argOrNull(args, "notes-dir") ?: return error("list <notes-dir>")
    val dir = NoteReader.locateNotesDir(File(noteDir))
        ?: return error("no notes found under '$noteDir' (expected <dir>/<noteId>/note.json)")
    val notes = NoteReader.listNotes(dir)
    if (notes.isEmpty()) {
        println("No notes found under '$noteDir'")
        return
    }
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    println("found ${notes.size} note(s) in $dir")
    for (n in notes) {
        val pages = NoteReader.pageIndices(dir, n.id).size.coerceAtLeast(n.pages.size)
        println("${n.id.padEnd(24)} ${fmt.format(Date(n.updatedAt)).padEnd(16)} ${pages}頁  ${n.title}")
    }
}

private fun cmdExport(args: List<String>) {
    val positional = args.filter { !it.startsWith("-") }
    val notesPath = value(args, "--notes") ?: positional.getOrNull(0)
        ?: return error("export needs a notes directory (first positional or --notes <dir>)")
    val noteId = value(args, "--id") ?: positional.getOrNull(1)
        ?: return error("export needs a note id (second positional or --id <id>)")
    val dir = NoteReader.locateNotesDir(File(notesPath))
        ?: return error("no notes found under '$notesPath'")
    val dpi = value(args, "--dpi")?.toFloatOrNull() ?: 300f
    val out = value(args, "--out", "-o") ?: return error("export needs --out <file>")
    val page = value(args, "--page")?.toIntOrNull()

    val note = NoteReader.loadNote(dir, noteId) ?: return error("note '$noteId' not found in $dir")
    val assetsDir = File(File(dir, noteId), "assets").takeIf { it.isDirectory }

    when {
        args.contains("--pdf") -> exportPdf(dir, note, assetsDir, dpi, out)
        args.contains("--jpeg") -> exportJpeg(dir, note, assetsDir, dpi, out, page)
        else -> return error("choose --pdf or --jpeg")
    }
}

private fun exportPdf(dir: File, note: Note, assetsDir: File?, dpi: Float, out: String) {
    val pages = NoteReader.loadPages(dir, note)
    if (pages.isEmpty()) return error("note has no pages")
    val jpegPages = pages.map { (page, strokes) ->
        val img = PageRenderer.renderPage(page, strokes, assetsDir, dpi)
        val bytes = jpegBytes(img)
        img.flush()
        JpegPage(page.widthMm, page.heightMm, img.width, img.height, bytes)
    }
    val file = File(out)
    if (PdfWriter.write(jpegPages, file)) {
        println("wrote ${file.canonicalPath} (${file.length()} bytes, ${jpegPages.size} page(s) @ ${dpi.toInt()}dpi)")
    } else {
        error("failed to write PDF to $out")
    }
}

private fun exportJpeg(dir: File, note: Note, assetsDir: File?, dpi: Float, out: String, page: Int?) {
    val pages = NoteReader.loadPages(dir, note)
    if (pages.isEmpty()) return error("note has no pages")
    val writeTo = { i: Int, bytes: ByteArray ->
        val file = File(outForPage(out, i, pages.size, page != null))
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        println("wrote ${file.canonicalPath} (${file.length()} bytes)")
    }
    if (page != null) {
        val (p, strokes) = pages.getOrNull(page) ?: return error("page $page out of range (note has ${pages.size} pages)")
        val img = PageRenderer.renderPage(p, strokes, assetsDir, dpi)
        writeTo(page, jpegBytes(img))
        img.flush()
    } else {
        pages.forEachIndexed { i, (p, strokes) ->
            val img = PageRenderer.renderPage(p, strokes, assetsDir, dpi)
            writeTo(i, jpegBytes(img))
            img.flush()
        }
    }
}

/** For multi-page JPEG export, `out` may be a prefix or a `something.jpg` base. */
private fun outForPage(out: String, pageIndex: Int, total: Int, single: Boolean): String {
    if (single || total <= 1) return out
    val base = when {
        out.endsWith(".jpg", ignoreCase = true) || out.endsWith(".jpeg", ignoreCase = true) ->
            out.substring(0, out.length - out.substringAfterLast('.').length - 1)
        else -> out
    }
    return "$base-p$pageIndex.jpg"
}

private fun jpegBytes(img: BufferedImage): ByteArray {
    val bos = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val ios: ImageOutputStream = ImageIO.createImageOutputStream(bos)
    writer.output = ios
    val param = writer.defaultWriteParam
    param.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
    param.compressionQuality = 0.92f
    writer.write(null, javax.imageio.IIOImage(img, null, null), param)
    writer.dispose()
    ios.close()
    return bos.toByteArray()
}

private fun cmdSample(args: List<String>) {
    val target = argOrNull(args, "target-dir") ?: return error("sample <target-dir>")
    val title = value(args, "--title") ?: "サンプルメモ"
    val dirName = value(args, "--dir-name")
    val f = File(target)
    f.mkdirs()
    SampleGenerator.generate(f, title, dirName)
}

private fun value(args: List<String>, vararg names: String): String? {
    val i = args.indexOfFirst { it in names }
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun argOrNull(args: List<String>, name: String): String? {
    val i = args.indexOfFirst { !it.startsWith("-") }
    return if (i >= 0) args[i] else null
}

private fun error(msg: String): Nothing {
    System.err.println("error: $msg")
    kotlin.system.exitProcess(2)
}

private fun printUsage() {
    println(
        """
        |stylus-export — ノートデータ（Android アプリのストレージ）を JPEG / PDF に書き出す CUI ツール

        |使い方:
        |  stylus-export list <notes-dir>
        |      ノート一覧を表示。devices 上では adb pull で取得したフォルダを指定する例:
        |      adb pull /sdcard/Android/data/com.stylusmemo.app/files/notes ./notes
        |      stylus-export list ./notes

        |  stylus-export export <notes-dir> <note-id> --pdf -o out.pdf [--dpi 300]
        |      全ページを1つの PDF に書き出す。

        |  stylus-export export <notes-dir> <note-id> --jpeg -o out.jpg [--page 2] [--dpi 300]
        |      --page N で単一ページを書き出し。省略時は全ページを out-p1.jpg, out-p2.jpg … に保存。

        |  stylus-export sample <target-dir> [--title "サンプル"] [--dir-name n-xxxx]
        |      実行確認用のサンプルノート（アプリと同じ形式）を生成する。

        |オプション:
        |  --dpi <n>   書き出し解像度 (既定 300)
        |  --notes <dir>  ノート保存先ディレクトリの別指定
        |    """.trimMargin(),
    )
}