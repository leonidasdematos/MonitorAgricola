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

    private var lastPose: GpsPose? = null
    private var lastAccuracy = params.maxAccM


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
        lastPose = null
        lastAccuracy = params.maxAccM
        accuracyAvg = 0.0
        accuracyCount = 0
        rejectAccuracy = 0
        rejectSpeed = 0
        rejectTime = 0
    }

    fun process(location: Location): GpsPose? {
        val now = clock.millis()
        val acc = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN
        val timestamp = location.time
        if (!location.hasAccuracy() || acc.isNaN() || acc > params.maxAccM) {
            rejectAccuracy++
            return predictFallback(timestamp, now)
        }

        if (abs(now - location.time) > 2000) {
            rejectTime++
            return predictFallback(timestamp, now)
        }

        val projection = ensureProjection(location.latitude, location.longitude)
        val curr = projection.toLocalMeters(location.latitude, location.longitude)

        val speedMpsRaw = when {
            location.hasSpeed() -> location.speed.toDouble().coerceAtLeast(0.0)
            hasLastAccepted -> {
                val dtSec = ((timestamp - lastMeasurementMillis) / 1000.0).coerceAtLeast(1e-3)
                val prev = projection.toLocalMeters(lastAcceptedLat, lastAcceptedLon)
                val dist = hypot(curr.x - prev.x, curr.y - prev.y)
                dist / dtSec
            }
            else -> 0.0
        }

        if (speedMpsRaw > 22.5) {
            rejectSpeed++
            return predictFallback(timestamp, now)
        }

        val candidate = OutlierGate.Candidate(
            x = curr.x,
            y = curr.y,
            timestampMillis = timestamp,
            speedMps = speedMpsRaw,
        )
        if (!outlierGate.evaluate(candidate)) {
            consecutiveRejects++
            if (consecutiveRejects > 8) {
                kalman.reset()
                headingFilter.reset()
                stationaryDetector.reset()
                outlierGate.reset()
                consecutiveRejects = 0
            }
            return predictFallback(timestamp, now)
        }
        consecutiveRejects = 0

        val dtSec = if (lastMeasurementMillis != 0L) ((timestamp - lastMeasurementMillis) / 1000.0).coerceAtLeast(1e-3) else 0.1
        lastMeasurementMillis = timestamp
        lastAcceptedLat = location.latitude
        lastAcceptedLon = location.longitude
        hasLastAccepted = true

        val accuracyClamped = acc.coerceIn(2.0, 15.0)
        val state = kalman.update(curr.x, curr.y, accuracyClamped, dtSec, speedMpsRaw)
        val kalmanSpeed = hypot(state.vx, state.vy)
        val stationary = stationaryDetector.update(state.x, state.y, state.vx, state.vy, kalmanSpeed, articulatedMode)
        val pose = buildPose(state, stationary, accuracyClamped, timestamp, now, projection)

        if (accuracyCount == 0) {
            accuracyAvg = accuracyClamped
        } else {
            accuracyAvg = (accuracyAvg * accuracyCount + accuracyClamped) / (accuracyCount + 1)
        }
        accuracyCount++
        lastAccuracy = accuracyClamped

        val emitted = emitPose(pose, now)
        logStatsIfNeeded(now)
        return emitted
    }

    private fun ensureProjection(lat: Double, lon: Double): ProjectionHelper {
        val current = projection
        if (current != null) return current
        val helper = ProjectionHelper(lat, lon)
        projection = helper
        return helper
    }

    private fun buildPose(
        state: Kalman2D.State,
        stationary: StationaryDetector.Result,
        accuracyM: Double,
        timestamp: Long,
        now: Long,
        projection: ProjectionHelper,
    ): GpsPose {
        val speed = hypot(state.vx, state.vy)
        val heading = headingFilter.update(
            state.vx,
            state.vy,
            speed,
            timestamp,
            stationary.headingDeg,
            null,
        )
        lastAlpha = heading.alphaUsed

        val compensated = leverArm.compensate(state.x, state.y, Math.toRadians(heading.headingDeg))
        tmpCoordinate.x = compensated.first
        tmpCoordinate.y = compensated.second
        val geo = projection.toLatLon(tmpCoordinate)

        lastLatency = max(0.0, now - timestamp.toDouble())


        return GpsPose(
            latitude = geo.latitude,
            longitude = geo.longitude,
            headingDeg = heading.headingDeg,
            speedMps = speed,
            accuracyM = accuracyM,
            timestampMillis = timestamp,
        )
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

    private fun emitPose(pose: GpsPose, now: Long): GpsPose? {
        val emitted = applyRateLimit(pose, now)
        lastPose = emitted ?: pose
        return emitted
    }

    private fun predictFallback(timestamp: Long, now: Long): GpsPose? {
        val projectionHelper = projection
        val state = predictState(timestamp)
        val pose = when {
            state != null && projectionHelper != null -> {
                val speed = hypot(state.vx, state.vy)
                val stationary = stationaryDetector.update(state.x, state.y, state.vx, state.vy, speed, articulatedMode)
                buildPose(state, stationary, lastAccuracy, timestamp, now, projectionHelper)
            }
            lastPose != null -> {
                lastLatency = max(0.0, now - timestamp.toDouble())
                lastPose!!.copy(timestampMillis = timestamp)
            }
            else -> return null
        }

        val emitted = emitPose(pose, now)
        logStatsIfNeeded(now)
        return emitted
    }

    private fun predictState(timestamp: Long): Kalman2D.State? {
        if (!kalman.isInitialized()) return null
        val lastTs = lastMeasurementMillis
        val dtSec = if (lastTs != 0L) ((timestamp - lastTs) / 1000.0) else 0.0
        val state = when {
            dtSec > 1e-3 -> kalman.predictOnly(dtSec)
            else -> kalman.currentState()
        }
        if (state != null && timestamp > lastMeasurementMillis) {
            lastMeasurementMillis = timestamp
        }
        return state
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