package org.pacer.app

import java.util.concurrent.TimeUnit

object RootCheck {

    /** What the check concluded. [UNKNOWN] is distinct from [DENIED] on purpose. */
    enum class State { GRANTED, DENIED, UNKNOWN }

    /** Where a root solution appears to come from, for the one line the screen shows. */
    data class Result(val state: State, val detail: String)

    /**
     * Runs `su -c id` on a worker thread and reports the answer.
     *
     * [onResult] is called exactly once, on the calling thread's executor as decided by the
     * caller; it is never called on the main thread by this object itself.
     *
     * The timeout is short by design. A `su` daemon that has not answered in a few seconds
     * is a manager waiting for the user to tap Allow, and blocking a startup screen on a
     * dialog the user has not seen is worse than reporting UNKNOWN: the screen can say
     * "allow it, then check again", which is actionable, while a frozen splash is not.
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

    /**
     * The root manager this shell looks like, from what the same shell can see.
     *
     * Only used for the sub-line, so it stays cheap: one `ls` per known manager rather
     * than a search of the filesystem.
     */
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
