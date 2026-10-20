package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCheckTest {

    @Test
    fun `a number inside the tolerance passes`() {
        val item = SelfCheck.number("speed", 3.0, 3.4, tolerance = 0.5, unit = " m/s")
        assertEquals(CheckItem.Verdict.OK, item.verdict)
        assertTrue(item.detail.contains("3.0"))
        assertTrue(item.detail.contains("3.4"))
    }

    @Test
    fun `a number outside the tolerance fails and shows both values`() {
        // The detail is the whole point: "FAIL" alone sends the user to logcat.
        val item = SelfCheck.number("speed", 3.0, 0.0, tolerance = 0.5, unit = " m/s")
        assertEquals(CheckItem.Verdict.FAIL, item.verdict)
        assertEquals("expected 3.0 m/s, got 0.0 m/s (tolerance 0.5 m/s)", item.detail)
    }

    @Test
    fun `the tolerance boundary itself passes`() {
        assertEquals(
            CheckItem.Verdict.OK,
            SelfCheck.number("position", 0.0, SelfCheck.POSITION_TOLERANCE_METERS, 1.0).verdict,
        )
    }

    @Test
    fun `text and flags compare exactly`() {
        assertEquals(CheckItem.Verdict.OK, SelfCheck.text("ssid", "Home", "Home").verdict)
        assertEquals(CheckItem.Verdict.FAIL, SelfCheck.text("ssid", "Home", "Neighbour").verdict)
        assertEquals(CheckItem.Verdict.OK, SelfCheck.flag("mock", false, false).verdict)
        assertEquals(CheckItem.Verdict.FAIL, SelfCheck.flag("mock", false, true).verdict)
        assertEquals(CheckItem.Verdict.OK, SelfCheck.exact("satellites", 9, 9).verdict)
    }

    @Test
    fun `a skip does not make a run pass`() {
        // A sheet full of skips must never claim PASS: that is the difference between
        // "checked and fine" and "never checked".
        val report = SelfCheckReport.of(
            "app",
            listOf(SelfCheck.pass("a", "ok"), SelfCheck.skip("b", "no permission")),
        )
        assertFalse(report.passed)
        assertTrue(report.noFailures)
        assertEquals("PASS (partial): 1 ok, 0 failed, 1 skipped", report.summaryLine())
    }

    @Test
    fun `all ok is a full pass`() {
        val report = SelfCheckReport.of("app", listOf(SelfCheck.pass("a", "ok")))
        assertTrue(report.passed)
        assertEquals("PASS: 1 ok, 0 failed, 0 skipped", report.summaryLine())
    }

    @Test
    fun `a failure wins the summary over skips`() {
        val report = SelfCheckReport.of(
            "app",
            listOf(
                SelfCheck.skip("a", "no permission"),
                SelfCheck.number("speed", 3.0, 0.0, 0.5),
            ),
        )
        assertEquals("FAIL: 0 ok, 1 failed, 1 skipped", report.summaryLine())
        assertFalse(report.passed)
    }

    @Test
    fun `an empty run is not a pass`() {
        // No probes at all is a wiring fault, not success.
        val report = SelfCheckReport.of("app", emptyList())
        assertEquals("NO CHECKS: 0 ok, 0 failed, 0 skipped", report.summaryLine())
        assertFalse(report.passed)
    }

    @Test
    fun `the copy text leads with failures and still lists passes`() {
        val text = SelfCheckReport.of(
            processLabel = "app",
            items = listOf(
                SelfCheck.pass("wifi-ssid", "\"Home\""),
                SelfCheck.number("speed", 3.0, 0.0, 0.5),
                SelfCheck.skip("cell", "no permission"),
            ),
            notes = listOf("run with a simulation active"),
        ).copyText(nowMs = 1234L)

        // The one line that decides whether anybody has to read on.
        assertTrue(text.contains("FAIL: 1 ok, 1 failed, 1 skipped"))
        // Failures before skips before passes; each section is complete.
        val failedAt = text.indexOf("FAILED")
        val skippedAt = text.indexOf("SKIPPED")
        val passedAt = text.indexOf("PASSED")
        assertTrue(failedAt in 0 until skippedAt)
        assertTrue(skippedAt < passedAt)
        assertTrue(text.contains("- speed: expected 3.0, got 0.0"))
        assertTrue(text.contains("- cell: no permission"))
        assertTrue(text.contains("- wifi-ssid: \"Home\""))
        assertTrue(text.contains("- run with a simulation active"))
        assertTrue(text.contains("time: 1234"))
    }
}
