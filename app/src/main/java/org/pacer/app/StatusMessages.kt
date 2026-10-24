package org.pacer.app

import org.pacer.motion.RouteClock

/**
 * What kind of thing the app is telling the user, which decides how long it stays.
 *
 * Sharing one TextView with the live run status would let a notice that a route was renamed
 * sit above a stopwatch for the rest of the session and read like part of it. Separating
 * them by lifetime is the whole point of this type.
 */
internal enum class NoticeKind {
    /** A consequence of something the user just did: saved, deleted, started. Goes away. */
    INFO,

    /** A long job is running. Stays until the job's own result replaces it. */
    PROGRESS,

    /** The thing the user asked for did not happen. Never goes away on its own. */
    FAILURE,
}

/**
 * How long a notice stays on screen, in milliseconds; 0 means "until something replaces it".
 *
 * A fixed timeout cannot work for text a human has to read: a two-line Chinese sentence is
 * not readable in four seconds, and pulling it away mid-sentence is worse than not showing it.
 * So the time follows the length, with a floor for short lines and a ceiling so a long one
 * still leaves eventually. A failure never leaves, because the user may not have been looking
 * at that moment and would then believe the run had started.
 */
internal fun noticeDurationMs(text: String, kind: NoticeKind): Long = when (kind) {
    NoticeKind.FAILURE, NoticeKind.PROGRESS -> 0L
    NoticeKind.INFO -> (INFO_BASE_MS + INFO_PER_CHAR_MS * text.length).coerceAtMost(INFO_MAX_MS)
}

/** A short confirmation is worth about a glance; this is the floor. */
private const val INFO_BASE_MS = 4_000L

private const val INFO_PER_CHAR_MS = 40L

/** Even a paragraph goes away; the record of it is on the page that changed. */
private const val INFO_MAX_MS = 12_000L

/**
 * When a notice shown at [startedAtMs] should disappear, on the same monotonic clock
 * [startedAtMs] came from; null means it has no timer and only its result can remove it.
 *
 * A deadline rather than a "time left" field: the lifetime belongs to the text, not to the
 * window being visible, so leaving the page and coming back only has to ask how much of the
 * original life remains. A deadline also makes an overdue notice impossible to mistake for
 * one that should be shown again, which a stored remainder can be.
 */
internal fun noticeDeadlineMs(startedAtMs: Long, durationMs: Long): Long? =
    if (durationMs <= 0L) null else startedAtMs + durationMs

/**
 * What is left of a notice at [nowMs]; 0 means it is already overdue and should be hidden
 * now, null means it has no timer at all.
 *
 * Clamped at zero so an overdue deadline can never turn into a negative delay, and so the
 * caller cannot mistake "already gone" for "show it again for no time".
 */
internal fun noticeRemainingMs(nowMs: Long, deadlineMs: Long?): Long? =
    deadlineMs?.let { (it - nowMs).coerceAtLeast(0L) }

/**
 * What the one status line can say, derived only from what is stored.
 *
 * Deliberately takes plain values rather than the stored config: this must stay a pure
 * function so a test can pin the rule below without building a whole configuration.
 */
internal enum class RunState {
    /** Nothing worth saying; the pause button being disabled already says it. */
    HIDDEN,

    /** The module has never bound, so no run can be started or observed. */
    UNBOUND,
    RUNNING,
    PAUSED,
}

/**
 * The state line's content, from the stored run and whether the config channel is bound.
 *
 * The rule that matters: this reads what is STORED, never what a button press intended. A
 * start that failed writes nothing, so it cannot leave the line claiming a run — the exact
 * lie this app has already shipped once and must not ship again.
 *
 * An unbound channel outranks "no run", because it is the fact that explains why pressing
 * Run keeps doing nothing, and it is true the whole time rather than for one moment.
 */
internal fun runStateOf(
    active: Boolean,
    paused: Boolean,
    bound: Boolean,
): RunState = when {
    active && paused -> RunState.PAUSED
    active -> RunState.RUNNING
    // No run stored: either nothing was ever pushed, or the run was stopped. Both are idle.
    // An unbound channel is worth a permanent line; a bound idle app is not.
    bound -> RunState.HIDDEN
    else -> RunState.UNBOUND
}

/**
 * The elapsed seconds the state line prints at [nowMs].
 *
 * This is a separate question from where the subject is. A straight run's position is
 * measured from [anchorAtMs], which every steering write moves forward, while the run itself
 * began at [startedAtMs] and has not restarted since. Printing the anchor difference made
 * dragging the joystick reset the stopwatch on every frame, so the line counted backwards.
 *
 * A route keeps its own clock, because only [RouteClock] knows how a pause freezes it.
 *
 * [anchorAtMs] is accepted and deliberately never read. It is the value that must not decide
 * this answer, and a test pins exactly that rather than trusting the caller to pick the right
 * field; keeping it in the signature also makes the mistake obvious at the call site instead
 * of silent. Plain values rather than the stored config, so the choice is reachable from a
 * JVM test, matching [runStateOf].
 */
@Suppress("UNUSED_PARAMETER")
internal fun runElapsedSeconds(
    onRoute: Boolean,
    startedAtMs: Long,
    anchorAtMs: Long,
    routeOffsetMs: Long,
    routeResumedAtMs: Long,
    routePaused: Boolean,
    nowMs: Long,
): Long {
    if (!onRoute) {
        // A run is always stored with a start, so this only fires on a hand-edited table.
        // Returning the age since the epoch would print "29437729:xx" instead of a stopwatch.
        if (startedAtMs <= 0L) return 0L
        return RouteClock.secondsSinceStart(startedAtMs, nowMs).toLong()
    }
    // A route that was never resumed reads its start; the same fallback Stored.routeSeconds
    // uses, so the line and the marker cannot describe different seconds.
    return RouteClock.secondsAt(
        offsetSec = routeOffsetMs / 1000.0,
        resumedAtMs = if (routeResumedAtMs > 0L) routeResumedAtMs else startedAtMs,
        nowMs = nowMs,
        paused = routePaused,
    ).toLong()
}
