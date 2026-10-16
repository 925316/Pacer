// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Audits a generated track the way a server-side anti-cheat heuristic would.
 *
 * The same [FixSample] stream Pacer emits is re-read as if it were an upload:
 * each rule inspects one independent tell and returns a [Finding] whose score
 * runs from 0.0 (clean) to 1.0 (certain fabrication). The findings are merged
 * into a single weighted [Report.risk] and mapped to a [Verdict].
 *
 * Design notes:
 * - Pure Kotlin/JVM, no Android and no external dependency.
 * - Every numeric knob lives in [Thresholds]; nothing is hard-coded in a rule.
 * - A rule with too little data returns null and is simply omitted, so it does
 *   not dilute the weighted average with a fake zero.
 * - [speed_step_correlation] uses the step rate implied by consecutive
 *   [FixSample.cumulativeSteps] values, so a caller can feed a track that has
 *   no cadence field at all.
 *
 * The heuristics below are original implementations; only the general ideas
 * (speed plausibility, spectral flatness, duplicate detection) are common
 * knowledge in the field.
 */
object TrackAudit {

    /** One red flag raised by a single rule. [score] is clamped to 0.0..1.0. */
    data class Finding(val rule: String, val score: Double, val detail: String)

    /** Coarse outcome bucket derived from [Report.risk]. */
    enum class Verdict { NORMAL, LOW_RISK, MEDIUM_RISK, HIGH_RISK }

    /** Audit result plus the weighted aggregate and its verdict. */
    data class Report(val findings: List<Finding>, val risk: Double, val verdict: Verdict)

    /** Axis-aligned lat/lon box; used by the optional allowed-zone rule. */
    data class GeoBounds(
        val minLat: Double,
        val minLon: Double,
        val maxLat: Double,
        val maxLon: Double,
    ) {
        fun contains(p: GeoPoint): Boolean =
            p.lat in minLat..maxLat && p.lon in minLon..maxLon
    }

    /**
     * Every numeric threshold, weight and cut point used by the auditor.
     *
     * Defaults are deliberately loose: a false positive on an honest human run
     * is worse than missing a subtle fake, so "warn" bands sit well above what
     * real activities look like and "hard" bands are near-certain tells.
     */
    data class Thresholds(
        // --- speed_physiological ---------------------------------------------
        // Sustained human running tops out near 6..7 m/s; 8 m/s is already
        // world-class, so anything past it is suspicious. 12 m/s is a vehicle.
        val maxSpeedWarnMps: Double = 8.0,
        val maxSpeedHardMps: Double = 12.0,
        // Elite acceleration is ~4 m/s^2; GNSS speed spikes can overshoot, so
        // warn at 4 and only call it certain at 10.
        val maxAccelWarnMps2: Double = 4.0,
        val maxAccelHardMps2: Double = 10.0,
        // Constant speed is itself a tell. We use the coefficient of variation
        // (std/mean) so the test is independent of the speed scale.
        val speedCvWarn: Double = 0.03,
        val speedCvHard: Double = 0.005,
        // Below this speed a sample is treated as standing still and excluded
        // from the variance estimate (pauses would otherwise not count).
        val movingSpeedMps: Double = 0.5,

        // --- noise_spectrum ---------------------------------------------------
        // Lag-1..5 autocorrelation of the step-length series. Real human and
        // GNSS jitter drive it low; a machine-smoothed ramp drives it high.
        val acfLagWarn: Double = 0.92,
        val acfLagHard: Double = 0.995,
        // Below this variance in the step-length series we flag "perfectly
        // uniform increments" outright.
        val varianceEpsilon: Double = 1e-9,
        // A track that never really moves is not evidence of cheating.
        val minMeanStepMeters: Double = 0.05,

        // --- accuracy_stability ----------------------------------------------
        // Reported horizontal accuracy should breathe. We look at the sliding
        // window with the most variation and only flag when even that is flat.
        val accuracyWindow: Int = 30,
        val accuracyStdWarnMeters: Double = 0.25,
        val accuracyStdHardMeters: Double = 0.02,

        // --- step_distance ----------------------------------------------------
        // steps * stride should land near the GPS distance. The plausible band
        // is generous because stride varies by person and terrain.
        val stepRatioMin: Double = 0.6,
        val stepRatioMax: Double = 1.4,
        val stepRatioMinHard: Double = 0.1,
        val stepRatioMaxHard: Double = 3.0,
        // Only judge step/distance agreement once the track is long enough.
        val minDistanceForStepCheckMeters: Double = 200.0,

        // --- speed_step_correlation ------------------------------------------
        // Speed and step rate move together in real motion. A weak correlation
        // means steps are decorative; a constant series means they are canned.
        val speedStepCorrMin: Double = 0.5,
        val speedStepCorrHard: Double = 0.05,
        val minCorrelationSamples: Int = 8,
        // Score applied when a series is perfectly constant (Pearson is
        // undefined because there is no variance).
        val constantSeriesScore: Double = 0.8,
        // Guards division when two fixes share (or nearly share) a timestamp.
        val stepRateDtEpsSec: Double = 1e-6,

        // --- trajectory_similarity -------------------------------------------
        // Coarse lat/lon grid (~11 m at the equator). Jaccard above "warn"
        // means the track retraces a previously seen track almost exactly.
        val similarityGridDeg: Double = 0.0001,
        val similarityWarn: Double = 0.70,
        val similarityHard: Double = 0.95,
        val minPointsForSimilarity: Int = 10,

        // --- zone_enforcement -------------------------------------------------
        // Fraction of points allowed to fall outside the permitted box before
        // it looks deliberate.
        val zoneOutsideWarnFraction: Double = 0.05,
        val zoneOutsideHardFraction: Double = 0.5,

        // --- checkpoint -------------------------------------------------------
        // Fraction of required checkpoints that may be missed.
        val checkpointMissWarnFraction: Double = 0.25,
        val checkpointMissHardFraction: Double = 1.0,

        // --- aggregation weights ---------------------------------------------
        // Hard, physical impossibilities outweigh soft statistical oddities.
        // monotonic_time is the strongest single tell, step_distance next.
        val weightSpeedPhysiological: Double = 1.0,
        val weightNoiseSpectrum: Double = 1.0,
        val weightAccuracyStability: Double = 0.5,
        val weightStepDistance: Double = 1.2,
        val weightSpeedStepCorrelation: Double = 0.8,
        val weightTrajectorySimilarity: Double = 1.0,
        val weightZoneEnforcement: Double = 0.8,
        val weightCheckpoint: Double = 0.8,
        val weightMonotonicTime: Double = 1.5,

        // --- verdict cut points ----------------------------------------------
        // risk < low -> NORMAL; < medium -> LOW_RISK; < high -> MEDIUM_RISK.
        val lowRiskCut: Double = 0.3,
        val mediumRiskCut: Double = 0.6,
        val highRiskCut: Double = 0.8,
    )

    /** Optional context supplied by the caller. */
    data class AuditOptions(
        /** Prior tracks the same account already uploaded (for duplicate check). */
        val priorTracks: List<List<GeoPoint>> = emptyList(),
        /** Allowed area; the zone rule is skipped when null. */
        val allowedZone: GeoBounds? = null,
        /** Checkpoints the route must pass; the checkpoint rule is skipped when empty. */
        val requiredCheckpoints: List<GeoPoint> = emptyList(),
        val checkpointRadiusMeters: Double = 30.0,
        val thresholds: Thresholds = Thresholds(),
    )

    /**
     * Run every rule over [fixes] and aggregate the result.
     * Returns a clean [Verdict.NORMAL] report for an empty input because there
     * is nothing to judge.
     */
    fun audit(fixes: List<FixSample>, options: AuditOptions = AuditOptions()): Report {
        if (fixes.isEmpty()) return Report(emptyList(), 0.0, Verdict.NORMAL)
        val t = options.thresholds
        val findings = ArrayList<Finding>(9)
        speedPhysiological(fixes, t)?.let { findings += it }
        noiseSpectrum(fixes, t)?.let { findings += it }
        accuracyStability(fixes, t)?.let { findings += it }
        stepDistance(fixes, t)?.let { findings += it }
        speedStepCorrelation(fixes, t)?.let { findings += it }
        trajectorySimilarity(fixes, options, t)?.let { findings += it }
        zoneEnforcement(fixes, options, t)?.let { findings += it }
        checkpoint(fixes, options, t)?.let { findings += it }
        findings += monotonicTime(fixes)
        val risk = weightedRisk(findings, t)
        return Report(findings, risk, verdictFor(risk, t))
    }

    /** Map an aggregate risk to a verdict using the configured cut points. */
    fun verdictFor(risk: Double, thresholds: Thresholds = Thresholds()): Verdict = when {
        risk >= thresholds.highRiskCut -> Verdict.HIGH_RISK
        risk >= thresholds.mediumRiskCut -> Verdict.MEDIUM_RISK
        risk >= thresholds.lowRiskCut -> Verdict.LOW_RISK
        else -> Verdict.NORMAL
    }

    // ---------------------------------------------------------------------
    // Rules
    // ---------------------------------------------------------------------

    /**
     * Rule 1: speed/acceleration plausibility.
     * Flags impossible top speed, impossible acceleration, and near-constant
     * speed (measured as the coefficient of variation over moving samples).
     */
    private fun speedPhysiological(fixes: List<FixSample>, t: Thresholds): Finding? {
        if (fixes.size < 2) return null
        val speeds = DoubleArray(fixes.size) { fixes[it].speedMps }
        if (speeds.any { !it.isFinite() || it < 0.0 }) {
            return Finding("speed_physiological", 1.0, "non-finite or negative speed")
        }
        val maxSpeed = speeds.maxOrNull() ?: return null
        var maxAccel = 0.0
        for (i in 1 until fixes.size) {
            val dt = fixes[i].tSec - fixes[i - 1].tSec
            if (!dt.isFinite() || dt <= t.stepRateDtEpsSec) continue
            maxAccel = max(maxAccel, abs(speeds[i] - speeds[i - 1]) / dt)
        }
        val moving = speeds.filter { it > t.movingSpeedMps }.toDoubleArray()
        // Only claim "constant speed" when there is enough motion to judge;
        // otherwise an all-stationary track would look suspicious for free.
        val cv = if (moving.size >= t.minCorrelationSamples) {
            val m = mean(moving)
            if (m > 0.0) std(moving) / m else 0.0
        } else {
            Double.POSITIVE_INFINITY
        }
        val sSpeed = scaleUp(maxSpeed, t.maxSpeedWarnMps, t.maxSpeedHardMps)
        val sAccel = scaleUp(maxAccel, t.maxAccelWarnMps2, t.maxAccelHardMps2)
        val sConst = if (cv.isFinite()) scaleDown(cv, t.speedCvHard, t.speedCvWarn) else 0.0
        val score = max(sSpeed, max(sAccel, sConst))
        return Finding(
            "speed_physiological",
            score,
            "maxSpeed=$maxSpeed maxAccel=$maxAccel cv=$cv",
        )
    }

    /**
     * Rule 2: noise spectrum of the displacement series.
     * A mechanical track has uniform step lengths (zero variance, flagged
     * outright) or contours that autocorrelate almost perfectly. We measure
     * the step-length series because it is scale-free and robust.
     */
    private fun noiseSpectrum(fixes: List<FixSample>, t: Thresholds): Finding? {
        if (fixes.size < 3) return null
        val steps = displacementSeries(fixes)
        val v = variance(steps)
        val m = mean(steps)
        if (v <= t.varianceEpsilon) {
            // Perfectly uniform increments only matter if the track moves.
            return if (m > t.minMeanStepMeters) {
                Finding("noise_spectrum", 1.0, "zero-variance displacement meanStep=$m")
            } else {
                null
            }
        }
        val acf1 = acfAt(steps, 1)
        var sum = 0.0
        var count = 0
        for (lag in 1..5) {
            if (lag < steps.size) {
                sum += acfAt(steps, lag)
                count++
            }
        }
        val acfMean = if (count > 0) sum / count else 0.0
        val score = max(
            scaleUp(acf1, t.acfLagWarn, t.acfLagHard),
            scaleUp(acfMean, t.acfLagWarn, t.acfLagHard),
        )
        return Finding("noise_spectrum", score, "acf1=$acf1 acfMean=$acfMean")
    }

    /**
     * Rule 3: accuracy stability.
     * Real GNSS accuracy jitters. We scan every sliding window and take the
     * one with the most variation: if even the liveliest window is flat, the
     * accuracy field is synthetic.
     */
    private fun accuracyStability(fixes: List<FixSample>, t: Thresholds): Finding? {
        if (fixes.size < 2) return null
        val acc = DoubleArray(fixes.size) { fixes[it].accuracyMeters.toDouble() }
        if (acc.any { !it.isFinite() }) return null
        val window = min(t.accuracyWindow, acc.size).coerceAtLeast(2)
        var maxStd = 0.0
        for (start in 0..acc.size - window) {
            val chunk = acc.copyOfRange(start, start + window)
            maxStd = max(maxStd, std(chunk))
        }
        val score = scaleDown(maxStd, t.accuracyStdHardMeters, t.accuracyStdWarnMeters)
        return Finding("accuracy_stability", score, "maxWindowStd=$maxStd")
    }

    /**
     * Rule 4: steps vs GPS distance.
     * Compares actual steps against the step count implied by the average
     * speed and the speed-dependent stride, and flags a track that covers a
     * long distance with almost no steps.
     */
    private fun stepDistance(fixes: List<FixSample>, t: Thresholds): Finding? {
        if (fixes.size < 2) return null
        var gps = fixes.last().distanceMeters - fixes.first().distanceMeters
        if (!gps.isFinite() || gps <= 0.0) gps = pathLength(fixes)
        if (gps < t.minDistanceForStepCheckMeters) return null
        val steps = (fixes.last().cumulativeSteps - fixes.first().cumulativeSteps).coerceAtLeast(0L)
        val totalTime = fixes.last().tSec - fixes.first().tSec
        val avgSpeed = if (totalTime > t.stepRateDtEpsSec) {
            gps / totalTime
        } else {
            val s = DoubleArray(fixes.size) { fixes[it].speedMps }
            mean(s)
        }
        val stride = StrideLinker.strideForSpeed(avgSpeed)
        val expected = gps / stride
        if (!expected.isFinite() || expected <= 0.0) return null
        val ratio = steps.toDouble() / expected
        val score = when {
            steps == 0L -> 1.0
            ratio < t.stepRatioMin -> scaleDown(ratio, t.stepRatioMinHard, t.stepRatioMin)
            ratio > t.stepRatioMax -> scaleUp(ratio, t.stepRatioMax, t.stepRatioMaxHard)
            else -> 0.0
        }
        return Finding("step_distance", score, "ratio=$ratio steps=$steps expected=$expected gps=$gps")
    }

    /**
     * Rule 5: speed vs step-rate correlation.
     * The step rate is derived from consecutive cumulative step counts, so no
     * cadence field is required. Weak correlation means the two streams were
     * generated independently; a constant series is even more suspicious.
     */
    private fun speedStepCorrelation(fixes: List<FixSample>, t: Thresholds): Finding? {
        if (fixes.size < t.minCorrelationSamples + 1) return null
        val speeds = ArrayList<Double>(fixes.size)
        val rates = ArrayList<Double>(fixes.size)
        for (i in 1 until fixes.size) {
            val dt = fixes[i].tSec - fixes[i - 1].tSec
            if (!dt.isFinite() || dt <= t.stepRateDtEpsSec) continue
            val dSteps = (fixes[i].cumulativeSteps - fixes[i - 1].cumulativeSteps).toDouble()
            if (dSteps < 0.0) return Finding("speed_step_correlation", 1.0, "non-monotonic steps")
            rates.add(dSteps / dt)
            speeds.add((fixes[i].speedMps + fixes[i - 1].speedMps) / 2.0)
        }
        if (speeds.size < t.minCorrelationSamples) return null
        val r = pearson(speeds.toDoubleArray(), rates.toDoubleArray())
        val score = if (r == null) {
            t.constantSeriesScore
        } else {
            scaleDown(abs(r), t.speedStepCorrHard, t.speedStepCorrMin)
        }
        return Finding("speed_step_correlation", score, "corr=$r")
    }

    /**
     * Rule 6: near-duplicate track detection.
     * Rasterizes the current track and each prior track onto a coarse grid and
     * computes the Jaccard similarity of the occupied cells. High overlap with
     * an earlier upload means the same route was replayed.
     */
    private fun trajectorySimilarity(
        fixes: List<FixSample>,
        options: AuditOptions,
        t: Thresholds,
    ): Finding? {
        if (options.priorTracks.isEmpty()) return null
        if (fixes.size < t.minPointsForSimilarity) return null
        val current = gridCells(fixes.map { it.point }, t.similarityGridDeg)
        if (current.isEmpty()) return null
        var best = 0.0
        for (prior in options.priorTracks) {
            if (prior.size < t.minPointsForSimilarity) continue
            val cells = gridCells(prior, t.similarityGridDeg)
            if (cells.isEmpty()) continue
            var inter = 0
            for (c in cells) if (c in current) inter++
            val union = current.size + cells.size - inter
            if (union > 0) best = max(best, inter.toDouble() / union)
        }
        val score = scaleUp(best, t.similarityWarn, t.similarityHard)
        return Finding("trajectory_similarity", score, "bestJaccard=$best")
    }

    /** Rule 7: fraction of points outside the optional allowed box. */
    private fun zoneEnforcement(fixes: List<FixSample>, options: AuditOptions, t: Thresholds): Finding? {
        val zone = options.allowedZone ?: return null
        var outside = 0
        for (f in fixes) if (!zone.contains(f.point)) outside++
        val fraction = outside.toDouble() / fixes.size
        val score = scaleUp(fraction, t.zoneOutsideWarnFraction, t.zoneOutsideHardFraction)
        return Finding("zone_enforcement", score, "outside=$outside/${fixes.size}")
    }

    /** Rule 8: fraction of required checkpoints that were never reached. */
    private fun checkpoint(fixes: List<FixSample>, options: AuditOptions, t: Thresholds): Finding? {
        if (options.requiredCheckpoints.isEmpty()) return null
        var met = 0
        for (cp in options.requiredCheckpoints) {
            if (fixes.any { GeoMath.haversine(it.point, cp) <= options.checkpointRadiusMeters }) met++
        }
        val missed = options.requiredCheckpoints.size - met
        val fraction = missed.toDouble() / options.requiredCheckpoints.size
        val score = scaleUp(fraction, t.checkpointMissWarnFraction, t.checkpointMissHardFraction)
        return Finding("checkpoint", score, "missed=$missed/${options.requiredCheckpoints.size}")
    }

    /** Rule 9: timestamps must be strictly increasing and non-NaN. */
    private fun monotonicTime(fixes: List<FixSample>): Finding {
        var violations = 0
        if (fixes[0].tSec.isNaN()) violations++
        for (i in 1 until fixes.size) {
            val a = fixes[i - 1].tSec
            val b = fixes[i].tSec
            if (a.isNaN() || b.isNaN() || b <= a) violations++
        }
        val score = if (violations > 0) 1.0 else 0.0
        return Finding("monotonic_time", score, "violations=$violations")
    }

    // ---------------------------------------------------------------------
    // Aggregation and helpers
    // ---------------------------------------------------------------------

    /** Weighted mean of the finding scores; missing rules are not counted. */
    private fun weightedRisk(findings: List<Finding>, t: Thresholds): Double {
        var num = 0.0
        var den = 0.0
        for (f in findings) {
            val w = weightOf(f.rule, t)
            if (w <= 0.0) continue
            num += w * f.score.coerceIn(0.0, 1.0)
            den += w
        }
        return if (den > 0.0) (num / den).coerceIn(0.0, 1.0) else 0.0
    }

    private fun weightOf(rule: String, t: Thresholds): Double = when (rule) {
        "speed_physiological" -> t.weightSpeedPhysiological
        "noise_spectrum" -> t.weightNoiseSpectrum
        "accuracy_stability" -> t.weightAccuracyStability
        "step_distance" -> t.weightStepDistance
        "speed_step_correlation" -> t.weightSpeedStepCorrelation
        "trajectory_similarity" -> t.weightTrajectorySimilarity
        "zone_enforcement" -> t.weightZoneEnforcement
        "checkpoint" -> t.weightCheckpoint
        "monotonic_time" -> t.weightMonotonicTime
        else -> 0.0
    }

    /** 0 when value <= start, 1 when value >= full. Used when large is bad. */
    private fun scaleUp(value: Double, start: Double, full: Double): Double {
        if (!value.isFinite()) return 1.0
        if (full <= start) return if (value >= full) 1.0 else 0.0
        return ((value - start) / (full - start)).coerceIn(0.0, 1.0)
    }

    /** 1 when value <= full, 0 when value >= start (full < start). Small is bad. */
    private fun scaleDown(value: Double, full: Double, start: Double): Double {
        if (!value.isFinite()) return 0.0
        if (start <= full) return if (value <= full) 1.0 else 0.0
        return ((start - value) / (start - full)).coerceIn(0.0, 1.0)
    }

    private fun displacementSeries(fixes: List<FixSample>): DoubleArray {
        val out = DoubleArray(fixes.size - 1)
        for (i in 1 until fixes.size) {
            out[i - 1] = GeoMath.haversine(fixes[i - 1].point, fixes[i].point)
        }
        return out
    }

    private fun pathLength(fixes: List<FixSample>): Double {
        var sum = 0.0
        for (i in 1 until fixes.size) sum += GeoMath.haversine(fixes[i - 1].point, fixes[i].point)
        return sum
    }

    private fun mean(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var s = 0.0
        for (v in x) s += v
        return s / x.size
    }

    /** Population variance, so acfAt stays consistent with lag-0 == 1. */
    private fun variance(x: DoubleArray): Double {
        if (x.size < 2) return 0.0
        val m = mean(x)
        var s = 0.0
        for (v in x) {
            val d = v - m
            s += d * d
        }
        return s / x.size
    }

    private fun std(x: DoubleArray): Double = sqrt(variance(x))

    /** Biased autocorrelation at [lag]; returns 0 when the series is flat. */
    private fun acfAt(x: DoubleArray, lag: Int): Double {
        val n = x.size
        if (lag <= 0 || lag >= n) return 0.0
        val v = variance(x)
        if (v <= 0.0) return 0.0
        val m = mean(x)
        var num = 0.0
        for (i in 0 until n - lag) num += (x[i] - m) * (x[i + lag] - m)
        return num / (n * v)
    }

    /** Pearson correlation, or null when either series has no variance. */
    private fun pearson(a: DoubleArray, b: DoubleArray): Double? {
        if (a.size != b.size || a.size < 2) return null
        val ma = mean(a)
        val mb = mean(b)
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in a.indices) {
            val da = a[i] - ma
            val db = b[i] - mb
            cov += da * db
            va += da * da
            vb += db * db
        }
        if (va <= 0.0 || vb <= 0.0) return null
        return cov / sqrt(va * vb)
    }

    private data class GridCell(val i: Long, val j: Long)

    private fun gridCells(points: List<GeoPoint>, gridDeg: Double): Set<GridCell> {
        if (gridDeg <= 0.0) return emptySet()
        val out = HashSet<GridCell>()
        for (p in points) {
            out.add(GridCell(floor(p.lat / gridDeg).toLong(), floor(p.lon / gridDeg).toLong()))
        }
        return out
    }
}
