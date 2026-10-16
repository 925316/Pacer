// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
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
        var amp = 0.0
        for (v in values) mean += v
        mean /= values.size
        for (v in values) amp = maxOf(amp, kotlin.math.abs(v - mean))
        if (amp <= 1e-9) return 0.0
        // Schmitt-trigger counting: noise around the mean must not register as
        // extra crossings, so only count transitions past a small hysteresis.
        val h = amp * 0.15
        var state = 0
        var crossings = 0
        for (v in values) {
            val d = v - mean
            if (state <= 0 && d > h) {
                if (state != 0) crossings++
                state = 1
            } else if (state >= 0 && d < -h) {
                if (state != 0) crossings++
                state = -1
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
