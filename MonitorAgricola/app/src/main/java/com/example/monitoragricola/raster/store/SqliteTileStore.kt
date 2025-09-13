// =============================================
// file: com/example/monitoragricola/raster/SqliteTileStore.kt
// =============================================
package com.example.monitoragricola.raster.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.monitoragricola.raster.RasterSnapshot
import com.example.monitoragricola.raster.SnapshotTile
import com.example.monitoragricola.raster.StoreTile
import com.example.monitoragricola.raster.TileData
import com.example.monitoragricola.raster.TileKey
import com.example.monitoragricola.raster.TileStore
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater


class SqliteTileStore(context: Context, dbName: String = "raster_tiles.db") : TileStore {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, dbName, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS tiles (" +
                        "tx INTEGER NOT NULL, ty INTEGER NOT NULL, rev INTEGER NOT NULL, layerMask INTEGER NOT NULL," +
                        "count BLOB NOT NULL, sections BLOB, rate BLOB, speed BLOB, lastStrokeId BLOB NOT NULL, frontStamp BLOB, " +
                        "PRIMARY KEY(tx,ty))"
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS snapshot_meta (id INTEGER PRIMARY KEY, originLat REAL, originLon REAL, resolutionM REAL, tileSize INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    override fun loadTile(tx: Int, ty: Int): StoreTile? {
        val db = helper.readableDatabase
        db.rawQuery("SELECT rev,layerMask,count,sections,rate,speed,lastStrokeId,frontStamp FROM tiles WHERE tx=? AND ty=?", arrayOf(tx.toString(), ty.toString())).use { c ->
            if (!c.moveToFirst()) return null
            val rev = c.getInt(0)
            val layerMask = c.getInt(1)
            val count = c.getBlob(2)
            val sections = c.getBlob(3)?.let { decompressIntArray(it) }
            val rate = c.getBlob(4)?.let { decompressFloatArray(it) }
            val speed = c.getBlob(5)?.let { decompressFloatArray(it) }
            val lastStrokeId = c.getBlob(6)?.let { decompressShortArray(it) } ?: return null
            val frontStamp = c.getBlob(7)?.let { decompressShortArray(it) }
            return StoreTile(
                tx = tx,
                ty = ty,
                rev = rev,
                count = count,
                sections = sections,
                rate = rate,
                speed = speed,
                lastStrokeId = lastStrokeId,
                frontStamp = frontStamp,
                layerMask = layerMask
            )        }
    }

    override suspend fun saveDirtyTilesAndClear(list: List<Pair<TileKey, TileData>>) {
        if (list.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues()
            for ((key, tile) in list) {
                cv.clear()
                cv.put("tx", key.tx)
                cv.put("ty", key.ty)
                cv.put("rev", tile.rev)
                cv.put("layerMask", tile.layerMask)
                cv.put("count", tile.count)
                cv.put("sections", tile.sections?.let { compressIntArray(it) })
                cv.put("rate", tile.rate?.let { compressFloatArray(it) })
                cv.put("speed", tile.speed?.let { compressFloatArray(it) })
                cv.put("lastStrokeId", compressShortArray(tile.lastStrokeId))
                cv.put("frontStamp", tile.frontStamp?.let { compressShortArray(it) })
                db.insertWithOnConflict("tiles", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                tile.dirty = false
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun snapshot(meta: RasterSnapshot) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM snapshot_meta")
            val cv = ContentValues()
            cv.put("id", 1)
            cv.put("originLat", meta.originLat)
            cv.put("originLon", meta.originLon)
            cv.put("resolutionM", meta.resolutionM)
            cv.put("tileSize", meta.tileSize)
            db.insert("snapshot_meta", null, cv)

            // Persist tiles
            val pairs = meta.tiles.map { st ->
                val td = TileData(
                    meta.tileSize,
                    st.count,
                    st.sections,
                    st.rate,
                    st.speed,
                    st.lastStrokeId,
                    st.frontStamp,
                    st.layerMask
                ).apply { rev = st.rev }
                TileKey(st.tx, st.ty) to td
            }
            kotlinx.coroutines.runBlocking { saveDirtyTilesAndClear(pairs) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun restore(): RasterSnapshot? {
        val db = helper.readableDatabase
        var meta: RasterSnapshot? = null
        db.rawQuery("SELECT originLat,originLon,resolutionM,tileSize FROM snapshot_meta WHERE id=1", emptyArray()).use { c ->
            if (c.moveToFirst()) {
                val originLat = c.getDouble(0)
                val originLon = c.getDouble(1)
                val res = c.getDouble(2)
                val tileSize = c.getInt(3)
                // Fetch all tiles
                val tiles = mutableListOf<SnapshotTile>()
                db.rawQuery("SELECT tx,ty,rev,layerMask,count,sections,rate,speed,lastStrokeId,frontStamp FROM tiles", emptyArray()).use { t ->
                    while (t.moveToNext()) {
                        val tx = t.getInt(0); val ty = t.getInt(1)
                        val rev = t.getInt(2); val layerMask = t.getInt(3)
                        val count = t.getBlob(4)
                        val sections = t.getBlob(5)?.let { decompressIntArray(it) }
                        val rate = t.getBlob(6)?.let { decompressFloatArray(it) }
                        val speed = t.getBlob(7)?.let { decompressFloatArray(it) }
                        val lastStrokeId = t.getBlob(8)?.let { decompressShortArray(it) } ?: ShortArray(tileSize*tileSize)
                        val frontStamp = t.getBlob(9)?.let { decompressShortArray(it) }
                        tiles += SnapshotTile(tx, ty, rev, layerMask, count, lastStrokeId, sections, rate, speed, frontStamp)
                    }
                }
                meta = RasterSnapshot(originLat, originLon, res, tileSize, tiles)
            }
        }
        return meta
    }

    override fun clear() {
        helper.writableDatabase.execSQL("DELETE FROM tiles"); helper.writableDatabase.execSQL("DELETE FROM snapshot_meta")
    }

    // ======== compactação util ========
    private fun compressFloatArray(arr: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(arr.size * 4)
        arr.forEach { bb.putFloat(it) }
        return deflate(bb.array())
    }
    private fun decompressFloatArray(data: ByteArray): FloatArray {
        val raw = inflate(data)
        val bb = ByteBuffer.wrap(raw)
        val out = FloatArray(raw.size / 4)
        for (i in out.indices) out[i] = bb.getFloat()
        return out
    }
    private fun compressIntArray(arr: IntArray): ByteArray {
        val bb = ByteBuffer.allocate(arr.size * 4)
        arr.forEach { bb.putInt(it) }
        return deflate(bb.array())
    }
    private fun decompressIntArray(data: ByteArray): IntArray {
        val raw = inflate(data)
        val bb = ByteBuffer.wrap(raw)
        val out = IntArray(raw.size / 4)
        for (i in out.indices) out[i] = bb.getInt()
        return out
    }
    private fun compressShortArray(arr: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(arr.size * 2)
        arr.forEach { bb.putShort(it) }
        return deflate(bb.array())
    }
    private fun decompressShortArray(data: ByteArray): ShortArray {
        val raw = inflate(data)
        val bb = ByteBuffer.wrap(raw)
        val out = ShortArray(raw.size / 2)
        for (i in out.indices) out[i] = bb.getShort()
        return out
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val def = Deflater(Deflater.BEST_SPEED)
        def.setInput(bytes)
        def.finish()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!def.finished()) {
            val n = def.deflate(buf)
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(bytes)
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && inf.needsInput()) break
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }
}