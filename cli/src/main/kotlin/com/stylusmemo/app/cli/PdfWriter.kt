package com.stylusmemo.app.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Minimal PDF writer that embeds each page as a JPEG image XObject (DCTDecode).
 * Page geometry is stored in points; images are scaled to exactly fill their page, keeping the
 * physical size correct at far higher fidelity than android's 72-dpi PdfDocument raster.
 */
object PdfWriter {

    private const val MM_PER_INCH = 25.4f
    private const val POINTS_PER_INCH = 72f

    /** [pages] = (pageWidthMm, pageHeightMm, content Jpeg bytes, jpegPixelWidth, jpegPixelHeight). */
    fun write(pages: List<JpegPage>, out: File): Boolean {
        if (pages.isEmpty()) return false
        val baos = ByteArrayOutputStream()
        val objOffsets = HashMap<Int, Long>()
        fun obj(num: Int, body: String) {
            objOffsets[num] = baos.size().toLong()
            baos.write("$num 0 obj\n".toByteArray())
            baos.write(body.toByteArray())
            baos.write("\nendobj\n".toByteArray())
        }

        val n = pages.size
        val kids = (0 until n).joinToString(" ") { "${3 + it * 3} 0 R" }
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [$kids] /Count $n >>")

        for (i in 0 until n) {
            val pageObj = 3 + i * 3
            val imgObj = 4 + i * 3
            val contentObj = 5 + i * 3
            val p = pages[i]
            val wPts = p.widthMm / MM_PER_INCH * POINTS_PER_INCH
            val hPts = p.heightMm / MM_PER_INCH * POINTS_PER_INCH

            obj(
                pageObj,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(wPts)} ${f(hPts)}] " +
                    "/Resources << /XObject << /Im$i $imgObj 0 R >> >> /Contents $contentObj 0 R >>",
            )

            objOffsets[imgObj] = baos.size().toLong()
            baos.write("$imgObj 0 obj\n".toByteArray())
            baos.write(
                ("<< /Type /XObject /Subtype /Image /Width ${p.imgW} /Height ${p.imgH} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                    "/Length ${p.jpegBytes.size} >>\nstream\n").toByteArray(),
            )
            baos.write(p.jpegBytes)
            baos.write("\nendstream\nendobj\n".toByteArray())

            val content = "q\n${f(wPts)} 0 0 ${f(hPts)} 0 0 cm\n/Im$i Do\nQ\n"
            obj(contentObj, "<< /Length ${content.toByteArray().size} >>\nstream\n${content}endstream")
        }

        val xrefPos = baos.size().toLong()
        val total = 2 + n * 3
        val sb = StringBuilder()
        sb.append("xref\n0 ${total + 1}\n")
        sb.append("0000000000 65535 f \n")
        for (num in 1..total) {
            sb.append(String.format(Locale.US, "%010d 00000 n \n", objOffsets[num] ?: 0L))
        }
        sb.append("trailer\n<< /Size ${total + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        baos.write(sb.toString().toByteArray())

        return try {
            out.writeBytes(baos.toByteArray())
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v)
}

data class JpegPage(
    val widthMm: Float,
    val heightMm: Float,
    val imgW: Int,
    val imgH: Int,
    val jpegBytes: ByteArray,
)