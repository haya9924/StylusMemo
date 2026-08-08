package com.stylusmemo.app.data

import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Binary codec for a page's stroke list. Format per stroke:
 * int32 colorArgb, float32 brushSize, float32 epsilon, int32 payloadLen, payload bytes
 * (payload = encoded [StrokeInputBatch]).
 */
object StrokeCodec {

    fun write(stream: OutputStream, strokes: List<Stroke>) {
        val out = DataOutputStream(stream)
        out.writeInt(strokes.size)
        for (s in strokes) {
            out.writeInt(s.brush.colorIntArgb)
            out.writeFloat(s.brush.size)
            out.writeFloat(s.brush.epsilon)
            val bytes = s.inputs.encode()
            out.writeInt(bytes.size)
            out.write(bytes)
        }
        out.flush()
    }

    fun read(stream: InputStream): List<Stroke> {
        val input = DataInputStream(stream)
        val count = input.readInt()
        val result = ArrayList<Stroke>(count)
        repeat(count) {
            val color = input.readInt()
            val size = input.readFloat()
            val epsilon = input.readFloat()
            val len = input.readInt()
            val bytes = ByteArray(len).also { input.readFully(it) }
            val batch = StrokeInputBatch.decode(bytes)
            result += Stroke(InkUtil.penBrush(color, size, epsilon), batch)
        }
        return result
    }

    fun toBytes(strokes: List<Stroke>): ByteArray =
        java.io.ByteArrayOutputStream().use { bos ->
            write(bos, strokes)
            bos.toByteArray()
        }

    fun fromBytes(bytes: ByteArray): List<Stroke> =
        java.io.ByteArrayInputStream(bytes).use { read(it) }
}
