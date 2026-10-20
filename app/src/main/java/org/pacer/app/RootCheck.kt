package org.pacer.app

import java.util.concurrent.TimeUnit

/**
 * Whether this device can run `su`, answered as a conclusion rather than as log text.
 *
 * Reported, not enforced: `su` can be absent on a device that runs the module some other
 * way, so the startup screen shows the state and still offers a way past it.
 *
 * `id` rather than the presence of a file: a `su` binary on PATH proves nothing, and the only
 * honest question is whether a root shell answers.
 */
object RootCheck {

    /** What the check concluded. [UNKNOWN] is distinct from [DENIED] on purpose. */
    enum class State { GRANTED, DENIED, UNKNOWN }

    /** Where a root solution appears to come from, for the one line the screen shows. */
    data class Result(val state: State, val detail: String)

    /**
     * Runs `su -c id` on a worker thread; [onResult] is called exactly once.
     *
     * The timeout is short by design: a `su` daemon that has not answered is a manager
     * waiting for the user to tap Allow, and UNKNOWN ("allow it, then check again") is more
     * useful than a frozen splash screen.
     */
    fun check(onResult: (Result) -> Unit) {
        Thread {
            onResult(run())
        }.apply {
            name = "pacer-root-check"
            isDaemon = true
        }.start()
    }

    private fun run(): Result {
        val outcome = try {
            invoke("su", "-c", "id")
        } catch (t: Throwable) {
            return Result(State.UNKNOWN, "su could not be started: ${t.javaClass.simpleName}")
        }
        val exited = outcome.exit
        if (exited == null) return Result(State.UNKNOWN, "su did not answer in time")
        if (exited != 0) {
            return Result(State.DENIED, "su exited $exited")
        }
        val text = outcome.stdout.trim()
        val granted = text.contains("uid=0")
        return if (granted) {
            Result(State.GRANTED, solutionName(text))
        } else {
            // A su that exits 0 but does not answer root is a broken permission state,
            // which is a denial with a reason, not an unknown.
            Result(State.DENIED, "su answered without uid=0")
        }
    }

    /** Best-effort name of the root manager, for the sub-line only. */
    private fun solutionName(shellWhoAmI: String): String {
        if (shellWhoAmI.contains("magisk")) return "Magisk"
        val probes = listOf(
            "/data/adb/magisk" to "Magisk",
            "/data/adb/ksu" to "KernelSU",
            "/data/adb/ap" to "APatch",
        )
        for ((path, name) in probes) {
            val present = try {
                val out = invoke("su", "-c", "test -d $path && echo yes")
                out.exit != null && out.stdout.contains("yes")
            } catch (_: Throwable) {
                false
            }
            if (present) return name
        }
        return "root shell"
    }

    private class Outcome(val stdout: String, val exit: Int?)

    /** null exit means the command was cut off by the timeout. */
    private fun invoke(vararg argv: String): Outcome {
        val process = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val finished = process.waitFor(2, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return Outcome("", null)
        }
        val text = process.inputStream.bufferedReader().use { it.readText() }
        return Outcome(text, process.exitValue())
    }
}
