// com/example/monitoragricola/raster/store/TileCodec.kt
package com.example.monitoragricola.raster.store

import java.io.*
import com.example.monitoragricola.raster.StoreTile
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private const val MAGIC = 0x52415354  // 'RAST'
private const val VERSION = 2

/** Flags de presença de camadas opcionais. */
private object F {
    const val SECTIONS = 1 shl 0
    const val RATE     = 1 shl 1
    const val SPEED    = 1 shl 2
    const val FRONT    = 1 shl 3
    const val STROKE   = 1 shl 4

}

/** count(Byte), lastStrokeId(Short) são sempre presentes. Demais por flags. */
object TileCodec {

    fun encode(st: StoreTile, tileSize: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(baos)).use { out ->
            out.writeInt(MAGIC)
            out.writeShort(VERSION)
            out.writeShort(tileSize)
            out.writeInt(st.tx); out.writeInt(st.ty)
            out.writeInt(st.rev)
            out.writeInt(st.layerMask)

            var flags = 0
            if (st.sections != null) flags = flags or F.SECTIONS
            if (st.rate     != null) flags = flags or F.RATE
            if (st.speed    != null) flags = flags or F.SPEED
            if (st.frontStamp != null) flags = flags or F.FRONT
            if (st.lastStrokeId != null) flags = flags or F.STROKE

            out.writeInt(flags)

            // obrigatórios
            writeByteArray(out, st.count)

            // opcionais
            if (st.lastStrokeId != null) writeShortArray(out, st.lastStrokeId)
            if (st.sections != null) writeIntArray(out, st.sections)
            if (st.rate     != null) writeFloatArray(out, st.rate)
            if (st.speed    != null) writeFloatArray(out, st.speed)
            if (st.frontStamp != null) writeShortArray(out, st.frontStamp)
        }
        return baos.toByteArray()
    }

    fun decode(bytes: ByteArray): Decoded {
        DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { inp ->
            val magic = inp.readInt()
            require(magic == MAGIC) { "bad magic" }
            val ver = inp.readShort().toInt()
            require(ver == VERSION) { "bad version $ver" }
            val tileSize = inp.readShort().toInt()
            val tx = inp.readInt()
            val ty = inp.readInt()
            val rev = inp.readInt()
            val layerMask = inp.readInt()
            val flags = inp.readInt()

            val count = readByteArray(inp)
            val lastStroke = if ((flags and F.STROKE) != 0) readShortArray(inp) else null

            val sections = if ((flags and F.SECTIONS) != 0) readIntArray(inp) else null
            val rate     = if ((flags and F.RATE)     != 0) readFloatArray(inp) else null
            val speed    = if ((flags and F.SPEED)    != 0) readFloatArray(inp) else null
            val front    = if ((flags and F.FRONT)    != 0) readShortArray(inp) else null

            return Decoded(tileSize, StoreTile(tx, ty, rev, layerMask, count, sections, rate, speed, lastStroke, front))
        }
    }

    data class Decoded(val tileSize: Int, val storeTile: StoreTile)
}

private fun writeByteArray(out: DataOutputStream, a: ByteArray) {
    out.writeInt(a.size); out.write(a)
}
private fun writeShortArray(out: DataOutputStream, a: ShortArray) {
    out.writeInt(a.size)
    for (v in a) out.writeShort(v.toInt())
}
private fun writeIntArray(out: DataOutputStream, a: IntArray) {
    out.writeInt(a.size)
    for (v in a) out.writeInt(v)
}
private fun writeFloatArray(out: DataOutputStream, a: FloatArray) {
    out.writeInt(a.size)
    for (v in a) out.writeFloat(v)
}

private fun readByteArray(inp: DataInputStream): ByteArray {
    val n = inp.readInt()
    val buf = ByteArray(n)
    inp.readFully(buf)
    return buf
}
private fun readShortArray(inp: DataInputStream): ShortArray {
    val n = inp.readInt()
    val a = ShortArray(n)
    for (i in 0 until n) a[i] = inp.readShort()
    return a
}
private fun readIntArray(inp: DataInputStream): IntArray {
    val n = inp.readInt()
    val a = IntArray(n)
    for (i in 0 until n) a[i] = inp.readInt()
    return a
}
private fun readFloatArray(inp: DataInputStream): FloatArray {
    val n = inp.readInt()
    val a = FloatArray(n)
    for (i in 0 until n) a[i] = inp.readFloat()
    return a
}
