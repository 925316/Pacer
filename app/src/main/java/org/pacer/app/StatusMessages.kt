package org.pacer.app

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
