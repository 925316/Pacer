package org.pacer.motion

/**
 * Result of one self-check probe: what was expected, what the device actually said.
 *
 * The point of this type is that a verdict is computed, never asserted by hand. A
 * failed check has to carry both numbers, because "FAIL" alone sends the user to
 * logcat, while "expected 3.000 m/s, got 0.000" usually names the fault outright.
 *
 * [SKIP] is a first-class verdict rather than a quiet pass. Most probes read an API
 * the app may not be allowed to call (no location permission, WiFi radio off), and
 * reporting those as OK would make the sheet claim coverage it never had.
 */
data class CheckItem(
    val name: String,
    val verdict: Verdict,
    val detail: String,
) {
    enum class Verdict { OK, FAIL, SKIP }

    val isFail: Boolean get() = verdict == Verdict.FAIL
}

/**
 * One run of the self-check, as the panel shows it and as the copy button exports it.
 *
 * The text form is deliberately plain: it is meant to be pasted into a chat or an
 * issue, so it starts with the one line that decides whether anything needs reading,
 * then lists only what is not OK, then everything.
 */
class SelfCheckReport(
    /** Which process ran the probes, so a pasted report is not ambiguous. */
    val processLabel: String,
    val items: List<CheckItem>,
    /** Free-form notes: what could not be checked here and why. */
    val notes: List<String> = emptyList(),
) {
    val okCount: Int get() = items.count { it.verdict == CheckItem.Verdict.OK }
    val failCount: Int get() = items.count { it.verdict == CheckItem.Verdict.FAIL }
    val skipCount: Int get() = items.count { it.verdict == CheckItem.Verdict.SKIP }

    /** True only when every probe ran and passed; skips do not make a run pass. */
    val passed: Boolean get() = failCount == 0 && skipCount == 0 && items.isNotEmpty()

    /** True when nothing failed, regardless of how much was skipped. */
    val noFailures: Boolean get() = failCount == 0

    fun summaryLine(): String {
        val verdict = when {
            items.isEmpty() -> "NO CHECKS"
            failCount > 0 -> "FAIL"
            skipCount > 0 -> "PASS (partial)"
            else -> "PASS"
        }
        return "$verdict: $okCount ok, $failCount failed, $skipCount skipped"
    }

    /**
     * The copyable form.
     *
     * Failures first and in full, because that is the part somebody will read; the
     * passes are one line each so the report stays short enough to paste.
     */
    fun copyText(nowMs: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder()
        sb.append("Pacer self-check\n")
        sb.append("process: ").append(processLabel).append('\n')
        sb.append("time: ").append(nowMs).append('\n')
        sb.append(summaryLine()).append('\n')

        val failed = items.filter { it.isFail }
        if (failed.isNotEmpty()) {
            sb.append("\nFAILED\n")
            for (item in failed) sb.append("- ").append(item.name).append(": ")
                .append(item.detail).append('\n')
        }
        val skipped = items.filter { it.verdict == CheckItem.Verdict.SKIP }
        if (skipped.isNotEmpty()) {
            sb.append("\nSKIPPED\n")
            for (item in skipped) sb.append("- ").append(item.name).append(": ")
                .append(item.detail).append('\n')
        }
        val passed = items.filter { it.verdict == CheckItem.Verdict.OK }
        if (passed.isNotEmpty()) {
            sb.append("\nPASSED\n")
            for (item in passed) sb.append("- ").append(item.name).append(": ")
                .append(item.detail).append('\n')
        }
        if (notes.isNotEmpty()) {
            sb.append("\nNOTES\n")
            for (note in notes) sb.append("- ").append(note).append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** Builds the report, dropping nothing: an empty list is itself a finding. */
        fun of(processLabel: String, items: List<CheckItem>, notes: List<String> = emptyList()) =
            SelfCheckReport(processLabel, items, notes)
    }
}

/**
 * Turns a comparison into a [CheckItem], so every probe states its tolerance once.
 *
 * Separated from the Android reads so the pass/fail boundary can be unit tested: an
 * off-by-one in a tolerance is otherwise only visible on a device, and only when the
 * value happens to land near the edge.
 */
object SelfCheck {

    /** Tolerance for a position: the injected fix carries jitter and rounding. */
    const val POSITION_TOLERANCE_METERS = 1.0

    fun number(
        name: String,
        expected: Double,
        actual: Double,
        tolerance: Double,
        unit: String = "",
    ): CheckItem {
        val delta = kotlin.math.abs(expected - actual)
        val ok = delta <= tolerance
        return CheckItem(
            name = name,
            verdict = if (ok) CheckItem.Verdict.OK else CheckItem.Verdict.FAIL,
            detail = "expected $expected$unit, got $actual$unit (tolerance $tolerance$unit)",
        )
    }

    fun exact(name: String, expected: Int, actual: Int): CheckItem = CheckItem(
        name = name,
        verdict = if (expected == actual) CheckItem.Verdict.OK else CheckItem.Verdict.FAIL,
        detail = "expected $expected, got $actual",
    )

    fun flag(name: String, expected: Boolean, actual: Boolean): CheckItem = CheckItem(
        name = name,
        verdict = if (expected == actual) CheckItem.Verdict.OK else CheckItem.Verdict.FAIL,
        detail = "expected $expected, got $actual",
    )

    fun text(name: String, expected: String, actual: String): CheckItem = CheckItem(
        name = name,
        verdict = if (expected == actual) CheckItem.Verdict.OK else CheckItem.Verdict.FAIL,
        detail = "expected \"$expected\", got \"$actual\"",
    )

    /** A probe that ran: the caller has already decided it holds. */
    fun pass(name: String, detail: String): CheckItem =
        CheckItem(name, CheckItem.Verdict.OK, detail)

    /**
     * A probe that could not run. The reason is required, because "skipped" without
     * one is indistinguishable from a probe somebody forgot to wire up.
     */
    fun skip(name: String, reason: String): CheckItem =
        CheckItem(name, CheckItem.Verdict.SKIP, reason)
}
