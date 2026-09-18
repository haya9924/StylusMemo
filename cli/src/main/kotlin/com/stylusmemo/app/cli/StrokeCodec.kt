package com.stylusmemo.app.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A point decoded from an Ink stroke, in page millimeters (stroke space). */
data class InkPoint(val x: Float, val y: Float, val tSeconds: Float, val pressure: Float)

/** A single decoded stroke. [colorArgb] is ARGB in the upper 32 bits, [sizeMm] is the pen width. */
data class DecodedStroke(
    val colorArgb: Int,
    val sizeMm: Float,
    val epsilon: Float,
    val points: List<InkPoint>,
)

/**
 * Reads the `page-<n>.bin` files written by the app's [com.stylusmemo.app.data.StrokeCodec].
 *
 * File layout per page: int32 strokeCount, then per stroke
 * `int32 colorArgb, float32 size, float32 epsilon, int32 payloadLen, payload`,
 * where the payload is a gzip-compressed `ink.proto.CodedStrokeInputBatch` protobuf
 * (see https://github.com/androidx/androidx tree androidx-main ink/ink-storage).
 *
 * A CodedStrokeInputBatch stores x/y/elapsed-time/pressure as CodedNumericRun deltas:
 * `value[n] = offset + scale * (deltas[0] + ... + deltas[n])`.
 */
object StrokeCodec {

    fun decodePage(file: File): List<DecodedStroke> = file.inputStream().buffered().use { decodePage(it) }

    fun decodePage(input: java.io.InputStream): List<DecodedStroke> {
        val din = DataInputStream(input)
        val out = ArrayList<DecodedStroke>()
        val count = din.readInt()
        repeat(count) {
            val color = din.readInt()
            val size = din.readFloat()
            val epsilon = din.readFloat()
            val len = din.readInt()
            val payload = ByteArray(len).also { din.readFully(it) }
            out += DecodedStroke(color, size, epsilon, decodeBatch(payload))
        }
        return out
    }

    fun encodePage(strokes: List<DecodedStroke>): ByteArray {
        val bos = ByteArrayOutputStream()
        val dout = java.io.DataOutputStream(bos)
        dout.writeInt(strokes.size)
        for (s in strokes) {
            dout.writeInt(s.colorArgb)
            dout.writeFloat(s.sizeMm)
            dout.writeFloat(s.epsilon)
            val payload = encodeBatch(s.points)
            dout.writeInt(payload.size)
            dout.write(payload)
        }
        dout.flush()
        return bos.toByteArray()
    }

    fun decodeBatch(payload: ByteArray): List<InkPoint> {
        val raw = GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
        val r = ProtoReader(raw)
        var xr: CodedNumericRun? = null
        var yr: CodedNumericRun? = null
        var tr: CodedNumericRun? = null
        var pr: CodedNumericRun? = null
        while (true) {
            val (field, wt) = r.readTag() ?: break
            when (field) {
                1 -> if (wt == 2) xr = readNumericRun(r) else skip(r, wt)
                2 -> if (wt == 2) yr = readNumericRun(r) else skip(r, wt)
                3 -> if (wt == 2) tr = readNumericRun(r) else skip(r, wt)
                4 -> if (wt == 2) pr = readNumericRun(r) else skip(r, wt)
                else -> skip(r, wt)
            }
        }
        val xs = xr?.decode() ?: return emptyList()
        val ys = yr?.decode() ?: return emptyList()
        val ts = tr?.decode()
        val ps = pr?.decode()
        val n = xs.size
        return (0 until n).map { i ->
            InkPoint(xs[i].toFloat(), ys[i].toFloat(), (ts?.get(i) ?: 0.0).toFloat(), (ps?.get(i) ?: 0.0).toFloat())
        }
    }

    /** Encodes points into a gzip-compressed CodedStrokeInputBatch (mirrors the Ink encoder). */
    fun encodeBatch(points: List<InkPoint>, mmScale: Float = 0.01f, timeScale: Float = 0.001f): ByteArray {
        val body = ByteArrayOutputStream()
        body.writeMessageField(1, encodeNumericRun(points.map { it.x }, mmScale))
        body.writeMessageField(2, encodeNumericRun(points.map { it.y }, mmScale))
        if (points.any { it.tSeconds != 0f }) {
            body.writeMessageField(3, encodeNumericRun(points.map { it.tSeconds }, timeScale))
        }
        if (points.any { it.pressure != 0f }) {
            body.writeMessageField(4, encodeNumericRun(points.map { it.pressure }, 1f / 255f))
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(body.toByteArray()) }
        return out.toByteArray()
    }

    private fun encodeNumericRun(values: List<Float>, scale: Float): ByteArray {
        // Quantize like the Ink encoder: deltas[i] = q[i] - q[i-1] with q[-1] = 0.
        val deltas = IntArray(values.size) { i ->
            val q = Math.round(values[i] / scale)
            val prev = if (i == 0) 0 else Math.round(values[i - 1] / scale)
            q - prev
        }
        val out = ByteArrayOutputStream()
        val packed = ByteArrayOutputStream()
        for (d in deltas) writeVarint(packed, zigzagEncode(d))
        out.writeTag(1, 2)
        writeVarint(out, packed.size())
        out.write(packed.toByteArray())
        if (scale != 1f) {
            out.writeTag(2, 5)
            out.writeFixed32(scale.toRawBits())
        }
        return out.toByteArray()
    }

    private class CodedNumericRun(val scale: Float, val offset: Float, val deltas: IntArray) {
        fun decode(): DoubleArray {
            val out = DoubleArray(deltas.size)
            var cum = 0L
            for (i in deltas.indices) {
                cum += deltas[i]
                out[i] = offset + scale.toDouble() * cum
            }
            return out
        }
    }

    private fun readNumericRun(r: ProtoReader): CodedNumericRun {
        val len = r.readVarint().toInt()
        val end = r.pos + len
        val deltas = ArrayList<Int>()
        var scale = 1f
        var offset = 0f
        while (r.pos < end) {
            val (field, wt) = r.readTag() ?: break
            when {
                field == 1 && wt == 0 -> deltas.add(zigzagDecode(r.readVarint().toInt()))
                field == 1 && wt == 2 -> {
                    val plen = r.readVarint().toInt()
                    val pend = r.pos + plen
                    while (r.pos < pend) deltas.add(zigzagDecode(r.readVarint().toInt()))
                }
                field == 2 && wt == 5 -> scale = Float.fromBits(r.readFixed32())
                field == 3 && wt == 5 -> offset = Float.fromBits(r.readFixed32())
                else -> skip(r, wt)
            }
        }
        return CodedNumericRun(scale, offset, deltas.toIntArray())
    }

    private class ProtoReader(val b: ByteArray) {
        var pos = 0

        fun readTag(): Pair<Int, Int>? {
            if (pos >= b.size) return null
            val tag = readVarint()
            return (tag ushr 3).toInt() to (tag and 7).toInt()
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = b[pos++].toInt() and 0xFF
                result = result or ((byte.toLong() and 0x7F) shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }

        fun readFixed32(): Int {
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
                ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
    }

    private fun skip(r: ProtoReader, wt: Int) {
        when (wt) {
            0 -> r.readVarint()
            1 -> r.pos += 8
            2 -> r.pos += r.readVarint().toInt()
            5 -> r.pos += 4
            else -> throw IllegalArgumentException("unsupported wire type $wt")
        }
    }

    private fun zigzagEncode(n: Int): Int = (n shl 1) xor (n shr 31)

    private fun zigzagDecode(n: Int): Int = (n ushr 1) xor -(n and 1)

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value.toLong() and 0xFFFFFFFFL
        while (v and 0xFFFFFF80L != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun ByteArrayOutputStream.writeTag(field: Int, wireType: Int) = writeVarint(this, (field shl 3) or wireType)

    private fun ByteArrayOutputStream.writeFixed32(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeMessageField(field: Int, message: ByteArray) {
        writeTag(field, 2)
        writeVarint(this, message.size)
        write(message)
    }
}
