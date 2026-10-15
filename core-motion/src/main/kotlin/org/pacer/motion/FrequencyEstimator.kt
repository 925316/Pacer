package org.pacer.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal frequency estimator over an accelerometer trace: counts zero-crossings
 * of the detrended signal and converts the estimated period into SPM.
 *
 * This is a lightweight alternative to a full FFT that is good enough for
 * sanity checks / anti-spoofing self-tests (e.g. "does the produced
 * waveform actually contain a ~2 Hz component?"). Pure Kotlin.
 */
object FrequencyEstimator {

    fun zeroCrossingsPerMinute(samplesHz: Int, values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        var mean = 0.0
        for (v in values) mean += v
        mean /= values.size
        var crossings = 0
        var prevSign = (values[0] - mean) > 0
        for (i in 1 until values.size) {
            val sign = (values[i] - mean) > 0
            if (sign != prevSign) {
                crossings++
                prevSign = sign
            }
        }
        val seconds = values.size.toDouble() / samplesHz
        val hz = crossings / 2.0 / seconds
        return hz * 60.0
    }

    fun correlationHz(samplesHz: Int, values: DoubleArray, targetFreqHz: Double): Double {
        if (values.size < 4) return 0.0
        val n = values.size
        val w = 2 * PI * targetFreqHz / samplesHz
        var sumCos = 0.0
        var sumSin = 0.0
        for (i in values.indices) {
            val x = values[i] - (values.sum() / n)
            sumCos += x * cos(w * i)
            sumSin += x * sin(w * i)
        }
        return (sqrt(sumCos * sumCos + sumSin * sumSin)) / n
    }
}
