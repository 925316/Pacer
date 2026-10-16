// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class TrackAuditTest {

    // A route with five turns so the generated course is not a single straight
    // segment; total length is a bit over 2 km which clears the step-distance
    // minimum. Roughly 445 m per segment near the equator.
    private val tunRoute = RoutePath(
        listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.004),
            GeoPoint(0.004, 0.004),
            GeoPoint(0.004, 0.0),
            GeoPoint(0.008, 0.0),
            GeoPoint(0.008, 0.004),
        ),
    )

    private fun humanFixes(seed: Long = 1234L): List<FixSample> =
        RouteSimulator(tunRoute, MotionConfig.run()).simulate(dtSec = 1.0, seed = seed)

    private fun fix(
        t: Double,
        lat: Double,
        lon: Double,
        speed: Double = 0.0,
        dist: Double = 0.0,
        steps: Long = 0L,
        cadence: Double = 0.0,
        accuracy: Float = 5f,
    ): FixSample = FixSample(
        tSec = t,
        point = GeoPoint(lat, lon),
        speedMps = speed,
        bearingDeg = 0f,
        distanceMeters = dist,
        phase = MotionPhase.CRUISE,
        accuracyMeters = accuracy,
        altitudeMeters = 0.0,
        satellites = 10,
        signalDropout = false,
        cumulativeSteps = steps,
        cadenceSpm = cadence,
    )

    private fun finding(report: TrackAudit.Report, rule: String): TrackAudit.Finding? =
        report.findings.firstOrNull { it.rule == rule }

    /** Constant speed, perfectly straight line with correctly derived steps. */
    private fun constantLine(speed: Double = 3.0, n: Int = 80): List<FixSample> {
        val stride = StrideLinker.strideForSpeed(speed)
        val latStep = speed / 111320.0
        val out = ArrayList<FixSample>(n)
        for (i in 0 until n) {
            val dist = i * speed
            val acc = (4.5 + 0.5 * (i % 3)).toFloat() // jitter so rule 3 stays quiet
            out += fix(
                t = i.toDouble(),
                lat = i * latStep,
                lon = 0.0,
                speed = speed,
                dist = dist,
                steps = (dist / stride).roundToLong(),
                cadence = speed / stride * 60.0,
                accuracy = acc,
            )
        }
        return out
    }

    @Test
    fun `clean humanized run scores NORMAL`() {
        val report = TrackAudit.audit(humanFixes())
        assertTrue("risk=${report.risk} findings=${report.findings}", report.risk < 0.3)
        assertEquals(TrackAudit.Verdict.NORMAL, report.verdict)
    }

    @Test
    fun `constant speed straight line scores higher than humanized run`() {
        val human = TrackAudit.audit(humanFixes())
        val line = TrackAudit.audit(constantLine())
        assertTrue("line=${line.risk} human=${human.risk}", line.risk > human.risk)
        assertTrue("line=${line.risk}", line.risk > 0.3)
    }

    @Test
    fun `zero steps over two km triggers step_distance`() {
        val n = 11
        val out = (0 until n).map { i ->
            fix(
                t = i.toDouble(),
                lat = i * (200.0 / 111320.0),
                lon = 0.0,
                speed = 2.0,
                dist = i * 200.0,
                steps = 0L,
                accuracy = 5f,
            )
        }
        val f = finding(TrackAudit.audit(out), "step_distance")
        assertTrue("step_distance missing", f != null)
        assertEquals(1.0, f!!.score, 1e-9)
    }

    @Test
    fun `duplicate track triggers trajectory_similarity`() {
        val pts = (0 until 12).map { GeoPoint(it * 0.001, 0.0) }
        val out = pts.mapIndexed { i, p ->
            fix(t = i.toDouble(), lat = p.lat, lon = p.lon, speed = 1.0, dist = i * 100.0)
        }
        val report = TrackAudit.audit(out, TrackAudit.AuditOptions(priorTracks = listOf(pts)))
        val f = finding(report, "trajectory_similarity")
        assertTrue("trajectory_similarity missing", f != null)
        assertTrue("score=${f!!.score}", f.score > 0.9)
    }

    @Test
    fun `point outside zone triggers zone_enforcement`() {
        val out = (0 until 10).map { i ->
            fix(t = i.toDouble(), lat = if (i < 5) 0.0 else 0.05, lon = 0.0, speed = 1.0, dist = i * 10.0)
        }
        val zone = TrackAudit.GeoBounds(minLat = -0.01, minLon = -0.01, maxLat = 0.01, maxLon = 0.01)
        val report = TrackAudit.audit(out, TrackAudit.AuditOptions(allowedZone = zone))
        val f = finding(report, "zone_enforcement")
        assertTrue("zone_enforcement missing", f != null)
        assertTrue("score=${f!!.score}", f.score > 0.0)
    }

    @Test
    fun `points inside zone score zero`() {
        val out = (0 until 10).map { i ->
            fix(t = i.toDouble(), lat = 0.001 * i, lon = 0.0, speed = 1.0, dist = i * 5.0)
        }
        val zone = TrackAudit.GeoBounds(minLat = -0.01, minLon = -0.01, maxLat = 0.02, maxLon = 0.02)
        val report = TrackAudit.audit(out, TrackAudit.AuditOptions(allowedZone = zone))
        val f = finding(report, "zone_enforcement")
        assertTrue("zone_enforcement missing", f != null)
        assertEquals(0.0, f!!.score, 1e-9)
    }

    @Test
    fun `missing checkpoints trigger checkpoint`() {
        val out = (0 until 10).map { i ->
            fix(t = i.toDouble(), lat = 0.0, lon = 0.0, speed = 1.0, dist = i * 10.0)
        }
        val cps = listOf(GeoPoint(1.0, 1.0), GeoPoint(2.0, 2.0))
        val report = TrackAudit.audit(out, TrackAudit.AuditOptions(requiredCheckpoints = cps))
        val f = finding(report, "checkpoint")
        assertTrue("checkpoint missing", f != null)
        assertEquals(1.0, f!!.score, 1e-9)
    }

    @Test
    fun `reached checkpoint scores zero`() {
        val out = (0 until 10).map { i ->
            fix(t = i.toDouble(), lat = 0.0001 * i, lon = 0.0, speed = 1.0, dist = i * 5.0)
        }
        val report = TrackAudit.audit(
            out,
            TrackAudit.AuditOptions(requiredCheckpoints = listOf(GeoPoint(0.0, 0.0))),
        )
        val f = finding(report, "checkpoint")
        assertTrue("checkpoint missing", f != null)
        assertEquals(0.0, f!!.score, 1e-9)
    }

    @Test
    fun `constant accuracy triggers accuracy_stability`() {
        val out = (0 until 40).map { i ->
            fix(t = i.toDouble(), lat = i * 1e-4, lon = 0.0, speed = 1.0, dist = i * 1.0, accuracy = 5f)
        }
        val f = finding(TrackAudit.audit(out), "accuracy_stability")
        assertTrue("accuracy_stability missing", f != null)
        assertEquals(1.0, f!!.score, 1e-9)
    }

    @Test
    fun `uncorrelated speed and step rate triggers speed_step_correlation`() {
        // Mid-point speeds and per-second step counts are constructed so their
        // Pearson correlation is exactly zero (deviations sum to zero).
        val speeds = listOf(0.0, 2.0, 4.0, 0.0, 2.0, 4.0, 0.0, 2.0, 4.0, 0.0)
        val cumulative = listOf(0L, 1L, 3L, 6L, 8L, 11L, 12L, 15L, 16L, 18L)
        val out = speeds.indices.map { i ->
            fix(t = i.toDouble(), lat = i * 1e-4, lon = 0.0, speed = speeds[i], dist = i * 1.0, steps = cumulative[i])
        }
        val f = finding(TrackAudit.audit(out), "speed_step_correlation")
        assertTrue("speed_step_correlation missing", f != null)
        assertTrue("score=${f!!.score}", f.score > 0.9)
    }

    @Test
    fun `decreasing timestamps trigger monotonic_time`() {
        val out = listOf(
            fix(t = 0.0, lat = 0.0, lon = 0.0, speed = 1.0, dist = 0.0),
            fix(t = 1.0, lat = 1.0, lon = 0.0, speed = 1.0, dist = 1.0),
            fix(t = 0.5, lat = 2.0, lon = 0.0, speed = 1.0, dist = 2.0),
        )
        val f = finding(TrackAudit.audit(out), "monotonic_time")
        assertTrue("monotonic_time missing", f != null)
        assertEquals(1.0, f!!.score, 1e-9)
    }

    @Test
    fun `nan timestamps trigger monotonic_time`() {
        val out = listOf(
            fix(t = 0.0, lat = 0.0, lon = 0.0, speed = 1.0, dist = 0.0),
            fix(t = Double.NaN, lat = 1.0, lon = 0.0, speed = 1.0, dist = 1.0),
        )
        val f = finding(TrackAudit.audit(out), "monotonic_time")
        assertTrue("monotonic_time missing", f != null)
        assertEquals(1.0, f!!.score, 1e-9)
    }

    @Test
    fun `strictly increasing timestamps pass monotonic_time`() {
        val out = (0 until 5).map { i ->
            fix(t = i.toDouble(), lat = i * 1e-4, lon = 0.0, speed = 1.0, dist = i * 1.0)
        }
        val f = finding(TrackAudit.audit(out), "monotonic_time")
        assertTrue("monotonic_time missing", f != null)
        assertEquals(0.0, f!!.score, 1e-9)
    }

    @Test
    fun `constant increments trigger noise_spectrum zero variance`() {
        val stepLat = 3.0 / 111320.0
        val out = (0 until 30).map { i ->
            fix(t = i.toDouble(), lat = i * stepLat, lon = 0.0, speed = 3.0, dist = i * 3.0, steps = (i * 3.2).toLong())
        }
        val f = finding(TrackAudit.audit(out), "noise_spectrum")
        assertTrue("noise_spectrum missing", f != null)
        assertEquals(1.0, f!!.score, 1e-9)
    }

    @Test
    fun `humanized run has lively displacement and steps`() {
        val report = TrackAudit.audit(humanFixes())
        val noise = finding(report, "noise_spectrum")
        val stepDist = finding(report, "step_distance")
        assertTrue("noise_spectrum missing", noise != null)
        assertTrue("noise=${noise!!.score}", noise.score < 0.3)
        assertTrue("step_distance missing", stepDist != null)
        assertTrue("stepDist=${stepDist!!.score}", stepDist.score < 0.3)
    }

    @Test
    fun `verdict mapping honours cut points`() {
        assertEquals(TrackAudit.Verdict.NORMAL, TrackAudit.verdictFor(0.0))
        assertEquals(TrackAudit.Verdict.NORMAL, TrackAudit.verdictFor(0.2999))
        assertEquals(TrackAudit.Verdict.LOW_RISK, TrackAudit.verdictFor(0.3))
        assertEquals(TrackAudit.Verdict.LOW_RISK, TrackAudit.verdictFor(0.5999))
        assertEquals(TrackAudit.Verdict.MEDIUM_RISK, TrackAudit.verdictFor(0.6))
        assertEquals(TrackAudit.Verdict.MEDIUM_RISK, TrackAudit.verdictFor(0.7999))
        assertEquals(TrackAudit.Verdict.HIGH_RISK, TrackAudit.verdictFor(0.8))
        assertEquals(TrackAudit.Verdict.HIGH_RISK, TrackAudit.verdictFor(1.0))
    }
}
