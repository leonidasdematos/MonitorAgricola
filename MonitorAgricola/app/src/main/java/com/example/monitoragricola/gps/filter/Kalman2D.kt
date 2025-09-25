package com.example.monitoragricola.gps.filter

import kotlin.math.max

class Kalman2D(
    private val baseProcessPos: Double = 0.8,
    private val baseProcessVel: Double = 1.5,
) {

    data class State(
        val x: Double,
        val y: Double,
        val vx: Double,
        val vy: Double,
    )

    private val state = DoubleArray(4)
    private val covariance = DoubleArray(16)
    private var initialized = false
    private var lastQPos = baseProcessPos
    private var lastR = 1.0

    fun reset() {
        initialized = false
        state.fill(0.0)
        covariance.fill(0.0)
    }

    fun isInitialized(): Boolean = initialized

    fun update(x: Double, y: Double, measurementAcc: Double, dtSec: Double, speed: Double): State {
        val clampedDt = dtSec.coerceAtLeast(1e-3)
        if (!initialized) {
            state[0] = x
            state[1] = y
            state[2] = 0.0
            state[3] = 0.0
            val varPos = measurementAcc * measurementAcc
            covariance.fill(0.0)
            covariance[0] = varPos
            covariance[5] = varPos
            covariance[10] = baseProcessVel
            covariance[15] = baseProcessVel
            initialized = true
            lastQPos = baseProcessPos
            lastR = varPos
            return State(state[0], state[1], state[2], state[3])
        }

        predict(clampedDt, adaptProcessNoise(speed, clampedDt))
        val varPos = measurementAcc * measurementAcc
        lastR = varPos
        updateInternal(x, y, varPos)
        return State(state[0], state[1], state[2], state[3])
    }

    private fun adaptProcessNoise(speed: Double, dt: Double): Double {
        val boost = if (speed < 1.5) {
            val factor = max(1.0, 1.5 / speed.coerceAtLeast(0.2))
            baseProcessPos * factor
        } else {
            baseProcessPos * 0.6
        }
        lastQPos = boost * dt
        return lastQPos
    }

    private fun predict(dt: Double, qPos: Double) {
        val F = doubleArrayOf(
            1.0, 0.0, dt, 0.0,
            0.0, 1.0, 0.0, dt,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val temp = DoubleArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += F[i * 4 + k] * covariance[k * 4 + j]
                }
                temp[i * 4 + j] = sum
            }
        }
        val newCov = DoubleArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += temp[i * 4 + k] * F[j * 4 + k]
                }
                newCov[i * 4 + j] = sum
            }
        }

        covariance.indices.forEach { covariance[it] = newCov[it] }
        covariance[0] += qPos
        covariance[5] += qPos
        covariance[10] += baseProcessVel * dt
        covariance[15] += baseProcessVel * dt

        val newX = state[0] + state[2] * dt
        val newY = state[1] + state[3] * dt
        state[0] = newX
        state[1] = newY
    }

    private fun updateInternal(x: Double, y: Double, measurementVar: Double) {
        val hx = state[0]
        val hy = state[1]
        val yRes0 = x - hx
        val yRes1 = y - hy

        val s00 = covariance[0] + measurementVar
        val s01 = covariance[1]
        val s10 = covariance[4]
        val s11 = covariance[5] + measurementVar

        val det = s00 * s11 - s01 * s10
        if (det == 0.0) return
        val invS00 = s11 / det
        val invS01 = -s01 / det
        val invS10 = -s10 / det
        val invS11 = s00 / det

        val k0 = covariance[0] * invS00 + covariance[1] * invS10
        val k1 = covariance[0] * invS01 + covariance[1] * invS11
        val k2 = covariance[4] * invS00 + covariance[5] * invS10
        val k3 = covariance[4] * invS01 + covariance[5] * invS11
        val k4 = covariance[8] * invS00 + covariance[9] * invS10
        val k5 = covariance[8] * invS01 + covariance[9] * invS11
        val k6 = covariance[12] * invS00 + covariance[13] * invS10
        val k7 = covariance[12] * invS01 + covariance[13] * invS11

        state[0] += k0 * yRes0 + k1 * yRes1
        state[1] += k2 * yRes0 + k3 * yRes1
        state[2] += k4 * yRes0 + k5 * yRes1
        state[3] += k6 * yRes0 + k7 * yRes1

        val kh = DoubleArray(16)
        for (i in 0 until 4) {
            kh[i * 4 + 0] = if (i == 0) k0 else if (i == 1) k2 else if (i == 2) k4 else k6
            kh[i * 4 + 1] = if (i == 0) k1 else if (i == 1) k3 else if (i == 2) k5 else k7
        }

        val identity = doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )

        val factor = DoubleArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                factor[i * 4 + j] = identity[i * 4 + j] - kh[i * 4 + j]
            }
        }

        val newCov = DoubleArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += factor[i * 4 + k] * covariance[k * 4 + j]
                }
                newCov[i * 4 + j] = sum
            }
        }
        covariance.indices.forEach { covariance[it] = newCov[it] }
    }

    fun lastQ(): Double = lastQPos
    fun lastR(): Double = lastR
}