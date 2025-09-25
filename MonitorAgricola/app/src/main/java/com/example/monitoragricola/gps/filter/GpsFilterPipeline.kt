package com.example.monitoragricola.gps.filter

import android.location.Location
import android.util.Log
import com.example.monitoragricola.gps.LeverArmCompensator
import com.example.monitoragricola.gps.api.GpsPose
import com.example.monitoragricola.map.ProjectionHelper
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import org.locationtech.jts.geom.Coordinate

class GpsFilterPipeline(
    private val params: Params,
    private val kalman: Kalman2D,
    private val headingFilter: HeadingFilter,
    private val outlierGate: OutlierGate,
    private val stationaryDetector: StationaryDetector,
    private val leverArm: LeverArmCompensator,
    private val clock: Clock = Clock.systemUTC(),
) {

    data class Params(
        val maxAccM: Double = 8.0,
        val maxJumpM: Double = 6.0,
        val vHeadingMin: Double = 0.6,
        val emaAlphaLowSpeed: Double = 0.12,
        val emaAlphaHighSpeed: Double = 0.35,
        val antennaToImplementMeters: Double = 5.0,
        val lateralOffsetMeters: Double = 0.0,
        val articulatedModeEnabled: Boolean = true,
        val rateLimitHz: Double = 15.0,
    )

    private var projection: ProjectionHelper? = null
    private var lastAcceptedLat = 0.0
    private var lastAcceptedLon = 0.0
    private var hasLastAccepted = false
    private var lastMeasurementMillis = 0L

    private var consecutiveRejects = 0

    private var accuracyAvg = 0.0
    private var accuracyCount = 0
    private var rejectAccuracy = 0
    private var rejectSpeed = 0
    private var rejectTime = 0

    private var lastLog = 0L
    private var lastLatency = 0.0
    private var lastAlpha = params.emaAlphaLowSpeed
    private var articulatedMode = params.articulatedModeEnabled

    private val minIntervalMs = max(40L, (1000.0 / params.rateLimitHz).toLong())
    private val lastEmit = AtomicLong(0)
    private var pendingPose: GpsPose? = null
    private var pendingSince = 0L

    private val tmpCoordinate = Coordinate()

    init {
        leverArm.updateOffsets(params.antennaToImplementMeters, params.lateralOffsetMeters)
    }

    fun reset() {
        kalman.reset()
        headingFilter.reset()
        stationaryDetector.reset()
        outlierGate.reset()
        projection = null
        hasLastAccepted = false
        lastMeasurementMillis = 0L
        consecutiveRejects = 0
        pendingPose = null
        pendingSince = 0L
    }

    fun process(location: Location): GpsPose? {
        val now = clock.millis()
        val acc = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN
        if (!location.hasAccuracy() || acc.isNaN() || acc > params.maxAccM) {
            rejectAccuracy++
            return null
        }

        if (abs(now - location.time) > 2000) {
            rejectTime++
            return null
        }

        val projection = ensureProjection(location.latitude, location.longitude)
        val curr = projection.toLocalMeters(location.latitude, location.longitude)

        val speedMpsRaw = when {
            location.hasSpeed() -> location.speed.toDouble().coerceAtLeast(0.0)
            hasLastAccepted -> {
                val dtSec = ((location.time - lastMeasurementMillis) / 1000.0).coerceAtLeast(1e-3)
                val prev = projection.toLocalMeters(lastAcceptedLat, lastAcceptedLon)
                val dist = hypot(curr.x - prev.x, curr.y - prev.y)
                dist / dtSec
            }
            else -> 0.0
        }

        if (speedMpsRaw > 20.0) {
            rejectSpeed++
            return null
        }

        val candidate = OutlierGate.Candidate(
            x = curr.x,
            y = curr.y,
            timestampMillis = location.time,
            speedMps = speedMpsRaw,
        )
        if (!outlierGate.evaluate(candidate)) {
            consecutiveRejects++
            if (consecutiveRejects > 4) {
                kalman.reset()
                headingFilter.reset()
                stationaryDetector.reset()
                //projection = null
            }
            return null
        }
        consecutiveRejects = 0

        val dtSec = if (lastMeasurementMillis != 0L) ((location.time - lastMeasurementMillis) / 1000.0).coerceAtLeast(1e-3) else 0.1
        lastMeasurementMillis = location.time
        lastAcceptedLat = location.latitude
        lastAcceptedLon = location.longitude
        hasLastAccepted = true

        val accuracyClamped = acc.coerceIn(2.0, 15.0)
        val state = kalman.update(curr.x, curr.y, accuracyClamped, dtSec, speedMpsRaw)
        val kalmanSpeed = hypot(state.vx, state.vy)
        val stationary = stationaryDetector.update(state.x, state.y, state.vx, state.vy, kalmanSpeed, articulatedMode)
        val heading = headingFilter.update(
            state.vx,
            state.vy,
            kalmanSpeed,
            location.time,
            stationary.headingDeg,
            null,
        )
        lastAlpha = heading.alphaUsed

        val compensated = leverArm.compensate(state.x, state.y, Math.toRadians(heading.headingDeg))
        tmpCoordinate.x = compensated.first
        tmpCoordinate.y = compensated.second
        val geo = projection.toLatLon(tmpCoordinate)

        accuracyAvg = (accuracyAvg * accuracyCount + accuracyClamped) / (accuracyCount + 1)
        accuracyCount++

        lastLatency = max(0.0, now - location.time.toDouble())

        val pose = GpsPose(
            latitude = geo.latitude,
            longitude = geo.longitude,
            headingDeg = heading.headingDeg,
            speedMps = kalmanSpeed,
            accuracyM = accuracyClamped,
            timestampMillis = location.time,
        )

        val toEmit = applyRateLimit(pose, now)
        logStatsIfNeeded(now)
        return toEmit
    }

    private fun ensureProjection(lat: Double, lon: Double): ProjectionHelper {
        val current = projection
        if (current != null) return current
        val helper = ProjectionHelper(lat, lon)
        projection = helper
        return helper
    }

    private fun applyRateLimit(pose: GpsPose, now: Long): GpsPose? {
        val last = lastEmit.get()
        if (last == 0L) {
            lastEmit.set(now)
            pendingPose = null
            pendingSince = 0L
            return pose
        }
        val delta = now - last
        if (delta >= minIntervalMs) {
            lastEmit.set(now)
            pendingPose = null
            pendingSince = 0L
            return pose
        }
        pendingPose = pose
        if (pendingSince == 0L) pendingSince = now
        if (now - pendingSince >= 150) {
            lastEmit.set(now)
            val emit = pendingPose
            pendingPose = null
            pendingSince = 0L
            return emit
        }
        return null
    }

    private fun logStatsIfNeeded(now: Long) {
        if (now - lastLog < 2000) return
        lastLog = now
        val stats = outlierGate.stats
        Log.d(
            "GPS/FILTER",
            "accAvg=${"%.2f".format(accuracyAvg)} rejAcc=$rejectAccuracy rejSpeed=$rejectSpeed rejTime=$rejectTime " +
                    "rejOut=${stats.rejectedDistance + stats.rejectedAcceleration + stats.rejectedHeading} " +
                    "kalmanQ=${"%.3f".format(kalman.lastQ())} kalmanR=${"%.2f".format(kalman.lastR())} " +
                    "emaAlpha=${"%.2f".format(lastAlpha)} latency=${"%.0f".format(lastLatency)}ms articulated=$articulatedMode"
        )
    }

    fun setArticulatedMode(enabled: Boolean) {
        articulatedMode = enabled
        headingFilter.setArticulatedMode(enabled)
    }
}