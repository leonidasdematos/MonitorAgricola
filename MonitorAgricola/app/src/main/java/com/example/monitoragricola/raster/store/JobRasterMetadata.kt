package com.example.monitoragricola.raster.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.monitoragricola.raster.RasterTotals
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Metadados persistidos do raster de um trabalho.
 */
@Entity(tableName = "job_raster_metadata")
data class JobRasterMetadataEntity(
    @PrimaryKey val jobId: Long,
    val originLat: Double,
    val originLon: Double,
    val resolutionM: Double,
    val tileSize: Int,
    val totalOncePx: Long?,
    val totalOverlapPx: Long?,
    val sectionPx: ByteArray?,
    val rateSumBySection: ByteArray?,
    val rateCountBySection: ByteArray?,
    val rateSumByArea: Double?,
    val rateCountByArea: Long?
)

data class JobRasterMetadata(
    val jobId: Long,
    val originLat: Double,
    val originLon: Double,
    val resolutionM: Double,
    val tileSize: Int,
    val totals: RasterTotals? = null
)

fun JobRasterMetadataEntity.toDomain(): JobRasterMetadata {
    val totals = if (
        totalOncePx != null &&
        totalOverlapPx != null &&
        rateSumByArea != null &&
        rateCountByArea != null
    ) {
        val section = sectionPx?.toLongArray()
        val rateSum = rateSumBySection?.toDoubleArray()
        val rateCount = rateCountBySection?.toLongArray()
        if (section != null && rateSum != null && rateCount != null) {
            RasterTotals(
                totalOncePx = totalOncePx,
                totalOverlapPx = totalOverlapPx,
                sectionPx = section,
                rateSumBySection = rateSum,
                rateCountBySection = rateCount,
                rateSumByArea = rateSumByArea,
                rateCountByArea = rateCountByArea
            )
        } else {
            null
        }
    } else {
        null
    }


    return JobRasterMetadata(
        jobId = jobId,
        originLat = originLat,
        originLon = originLon,
        resolutionM = resolutionM,
        tileSize = tileSize,
        totals = totals
    )
}

fun JobRasterMetadata.toEntity(): JobRasterMetadataEntity {
    val totals = totals
    return JobRasterMetadataEntity(
        jobId = jobId,
        originLat = originLat,
        originLon = originLon,
        resolutionM = resolutionM,
        tileSize = tileSize,
        totalOncePx = totals?.totalOncePx,
        totalOverlapPx = totals?.totalOverlapPx,
        sectionPx = totals?.sectionPx?.toByteArray(),
        rateSumBySection = totals?.rateSumBySection?.toByteArray(),
        rateCountBySection = totals?.rateCountBySection?.toByteArray(),
        rateSumByArea = totals?.rateSumByArea,
        rateCountByArea = totals?.rateCountByArea
    )
}

private fun LongArray.toByteArray(): ByteArray {
    if (isEmpty()) return ByteArray(0)
    val buffer = ByteBuffer.allocate(size * java.lang.Long.BYTES).order(ByteOrder.BIG_ENDIAN)
    for (value in this) buffer.putLong(value)
    return buffer.array()
}

private fun DoubleArray.toByteArray(): ByteArray {
    if (isEmpty()) return ByteArray(0)
    val buffer = ByteBuffer.allocate(size * java.lang.Double.BYTES).order(ByteOrder.BIG_ENDIAN)
    for (value in this) buffer.putDouble(value)
    return buffer.array()
}

private fun ByteArray.toLongArray(): LongArray? {
    if (isEmpty()) return LongArray(0)
    if (size % java.lang.Long.BYTES != 0) return null
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)
    val arr = LongArray(size / java.lang.Long.BYTES)
    for (i in arr.indices) arr[i] = buffer.long
    return arr
}

private fun ByteArray.toDoubleArray(): DoubleArray? {
    if (isEmpty()) return DoubleArray(0)
    if (size % java.lang.Double.BYTES != 0) return null
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)
    val arr = DoubleArray(size / java.lang.Double.BYTES)
    for (i in arr.indices) arr[i] = buffer.double
    return arr
}

@Dao
interface JobRasterMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: JobRasterMetadataEntity)

    @Query("SELECT * FROM job_raster_metadata WHERE jobId = :jobId LIMIT 1")
    suspend fun select(jobId: Long): JobRasterMetadataEntity?

    @Query("DELETE FROM job_raster_metadata WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: Long)
}