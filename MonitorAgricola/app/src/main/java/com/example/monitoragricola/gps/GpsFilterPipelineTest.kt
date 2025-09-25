package com.example.monitoragricola.gps

import android.location.Location
import com.example.monitoragricola.gps.filter.GpsFilterPipeline
import com.example.monitoragricola.gps.filter.HeadingFilter
import com.example.monitoragricola.gps.filter.Kalman2D
import com.example.monitoragricola.gps.filter.OutlierGate
import com.example.monitoragricola.gps.filter.StationaryDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class GpsFilterPipelineTest {

    private fun createPipeline(params: GpsFilterPipeline.Params = GpsFilterPipeline.Params()): GpsFilterPipeline {
        val kalman = Kalman2D()
        val headingFilter = HeadingFilter(
            vHeadingMin = params.vHeadingMin,
            emaAlphaLowSpeed = params.emaAlphaLowSpeed,
            emaAlphaHighSpeed = params.emaAlphaHighSpeed,
            articulatedMode = params.articulatedModeEnabled,
        )
        val outlierGate = OutlierGate(
            OutlierGate.Params(
                maxJumpM = params.maxJumpM,
                maxAccelerationMps2 = 5.0,
                maxTurnRateDegPerSec = 120.0,
            )
        )
        val stationaryDetector = StationaryDetector(
            speedThreshold = params.vHeadingMin,
            jitterThresholdM = 0.25,
        )
        val leverArm = LeverArmCompensator(
            antennaToImplementMeters = params.antennaToImplementMeters,
            lateralOffsetMeters = params.lateralOffsetMeters,
        )
        val clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC)
        return GpsFilterPipeline(
            params = params,
            kalman = kalman,
            headingFilter = headingFilter,
            outlierGate = outlierGate,
            stationaryDetector = stationaryDetector,
            leverArm = leverArm,
            clock = clock,
        )
    }

    private fun loc(lat: Double, lon: Double, time: Long, acc: Float = 3f, speed: Float? = null): Location {
        val l = Location("test")
        l.latitude = lat
        l.longitude = lon
        l.time = time
        l.accuracy = acc
        if (speed != null) {
            l.speed = speed
        }
        return l
    }

    private fun latLonFromMeters(originLat: Double, originLon: Double, dx: Double, dy: Double): Pair<Double, Double> {
        val lat = originLat + dy / 111_320.0
        val lon = originLon + dx / (111_320.0 * cos(Math.toRadians(originLat)))
        return lat to lon
    }

    @Test
    fun sequenceFilteringProducesStableHeading() {
        val pipeline = createPipeline()
        val originLat = -23.0
        val originLon = -46.0
        val dt = 200L
        var time = 0L
        val headings = mutableListOf<Double>()
        val positions = buildList {
            // parado
            repeat(5) { add(0.0 to 0.0) }
            // aceleração reta (leste)
            for (i in 1..10) add(i * 1.2 to 0.0)
            // curva suave para norte
            for (i in 1..10) add(12.0 + 1.5 * cos(i / 10.0 * Math.PI / 2) to 1.5 * sin(i / 10.0 * Math.PI / 2))
            // parada
            repeat(5) { add(12.0 to 1.5) }
        }

        for ((dx, dy) in positions) {
            val (lat, lon) = latLonFromMeters(originLat, originLon, dx, dy)
            val speed = if (time == 0L) 0f else 1.5f
            val pose = pipeline.process(loc(lat, lon, time, speed = speed))
            if (pose != null) {
                headings.add(pose.headingDeg)
            }
            time += dt
        }

        assertTrue("Esperado receber headings filtrados", headings.size > 10)
        for (i in 1 until headings.size) {
            val diff = abs(((headings[i] - headings[i - 1] + 540) % 360) - 180)
            assertTrue("Heading variou demais: $diff", diff < 90)
        }
        val finalHeading = headings.last()
        assertTrue("Heading final não rotacionou para norte", finalHeading in 350.0..360.0 || finalHeading < 10.0)
    }

    @Test
    fun rejectsOutliersAndRecoversQuickly() {
        val pipeline = createPipeline()
        val originLat = -22.5
        val originLon = -47.5
        var time = 0L
        val good1 = latLonFromMeters(originLat, originLon, 0.0, 0.0)
        val good2 = latLonFromMeters(originLat, originLon, 2.0, 0.0)
        val good3 = latLonFromMeters(originLat, originLon, 4.0, 0.0)
        val bad = latLonFromMeters(originLat, originLon, 120.0, 80.0)

        pipeline.process(loc(good1.first, good1.second, time, speed = 0f))
        time += 200
        pipeline.process(loc(good2.first, good2.second, time, speed = 10f))
        time += 200
        val acceptedBeforeOutlier = pipeline.process(loc(good3.first, good3.second, time, speed = 10f))
        assertTrue(acceptedBeforeOutlier != null)

        time += 200
        val outlier = pipeline.process(loc(bad.first, bad.second, time, speed = 50f))
        assertTrue("Outlier deveria ser descartado", outlier == null)

        time += 200
        val recoveryLatLon = latLonFromMeters(originLat, originLon, 6.0, 0.2)
        val recovered = pipeline.process(loc(recoveryLatLon.first, recoveryLatLon.second, time, speed = 12f))
        assertTrue("Pipeline deve recuperar após outlier", recovered != null)
        assertTrue("Velocidade após recuperação deve ser plausível", recovered!!.speedMps < 15.0)
    }

    @Test
    fun leverArmCompensationMatchesHeadings() {
        val lever = LeverArmCompensator(5.0, 2.0)
        val headings = listOf(0.0, 90.0, 180.0, 270.0)
        val expected = listOf(
            Pair(-5.0, 2.0),
            Pair(-2.0, -5.0),
            Pair(5.0, -2.0),
            Pair(2.0, 5.0),
        )
        for (i in headings.indices) {
            val (x, y) = lever.compensate(0.0, 0.0, Math.toRadians(headings[i]))
            assertEquals(expected[i].first, x, 1e-6)
            assertEquals(expected[i].second, y, 1e-6)
        }
    }

    @Test
    fun articulatedModeLimitsHeadingRate() {
        val params = GpsFilterPipeline.Params(articulatedModeEnabled = true)
        val pipeline = createPipeline(params)
        pipeline.setArticulatedMode(true)
        val originLat = -20.0
        val originLon = -45.0
        var time = 0L

        val headings = mutableListOf<Double>()
        val positions = listOf(
            0.0 to 0.0,
            1.5 to 0.0,
            3.0 to 0.0,
            4.5 to 1.5,
            5.5 to 3.5,
        )

        for ((dx, dy) in positions) {
            val (lat, lon) = latLonFromMeters(originLat, originLon, dx, dy)
            val pose = pipeline.process(loc(lat, lon, time, speed = 8f))
            if (pose != null) headings.add(pose.headingDeg)
            time += 200
        }

        for (i in 1 until headings.size) {
            val diff = abs(((headings[i] - headings[i - 1] + 540) % 360) - 180)
            assertTrue("Modo articulado limitou variação angular", diff <= 40)
        }
    }

    @Test
    fun recoversFromNoisySCurve() {
        val pipeline = createPipeline()
        val originLat = -24.0
        val originLon = -50.0
        val random = Random(0)
        var time = 0L
        var accepted = 0
        var rejected = 0
        for (i in 0 until 120) {
            val t = i / 10.0
            val dx = 2.0 * t
            val dy = 5.0 * sin(t / 4.0)
            val noiseX = random.nextGaussian() * 3.0
            val noiseY = random.nextGaussian() * 3.0
            val outlier = (i % 30 == 15)
            val (lat, lon) = if (outlier) {
                latLonFromMeters(originLat, originLon, dx + 40, dy - 35)
            } else {
                latLonFromMeters(originLat, originLon, dx + noiseX, dy + noiseY)
            }
            val pose = pipeline.process(loc(lat, lon, time, speed = 6f))
            if (pose == null) rejected++ else accepted++
            time += 150
        }
        assertTrue("Precisamos de amostras aceitas", accepted > 60)
        assertTrue("Rejeições devem ser poucas", rejected in 5..40)
    }

    @Test
    fun processingBenchmarkIsFast() {
        val pipeline = createPipeline()
        val originLat = -25.0
        val originLon = -47.0
        var time = 0L
        val start = System.nanoTime()
        for (i in 0 until 1000) {
            val dx = i * 0.5
            val (lat, lon) = latLonFromMeters(originLat, originLon, dx, 0.2 * sin(i / 30.0))
            pipeline.process(loc(lat, lon, time, speed = 4f))
            time += 100
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000.0 / 1000.0
        assertTrue("Tempo médio por process() deve ser <0.2ms (foi ${"%.3f".format(elapsed)})", elapsed < 0.2)
    }
}

private fun Random.nextGaussian(): Double {
    var u = 0.0
    var v = 0.0
    while (u == 0.0) u = nextDouble()
    while (v == 0.0) v = nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.log(u)) * cos(2.0 * Math.PI * v)
}