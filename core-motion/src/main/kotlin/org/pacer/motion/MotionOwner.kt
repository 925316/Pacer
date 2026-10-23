package org.pacer.motion

/**
 * Which input currently owns the subject's speed and heading.
 *
 * One way to model this is a single `Mode` enum with mutually exclusive states,
 * the joystick being one of those states rather than a layer over the others.
 * Pacer instead stores the same facts as independent configuration keys - a
 * route, an interval plan, a flat speed - so nothing stops two of them from
 * being present at once. Ownership is therefore derived here, in one place, and
 * every writer consults it.
 *
 * The derived answer is what the run actually does, not what the UI happens to
 * show: a route beats a plan because the route engine builds its samples from the
 * route and only uses the plan as the speed curve inside that build; a plan beats
 * the flat speed because every position formula gives the plan precedence.
 */
enum class MotionOwner {

    /** No run is stored. */
    NONE,

    /** A route run: position, speed and heading come from the route and its clock. */
    ROUTE,

    /**
     * A route run whose clock is frozen at a chosen point.
     *
     * The run still owns the position: it is the route's own sample at the frozen
     * time, with the reported speed zero. Hand steering is refused here for the same
     * reason it is refused on a running route, and for one more: the step counter is
     * anchored to the run's start, so a manual detour would either freeze the count
     * while the subject moves or jump it when the route resumes.
     */
    ROUTE_PAUSED,

    /** A straight-line run whose speed comes from an interval plan. */
    PLAN,

    /** A straight-line run steered by hand. */
    HAND,
    ;

    /**
     * True when the floating joystick may write speed and heading.
     *
     * Only [HAND] qualifies. A route derives distance from the clock, so writing a
     * speed there would move the subject along its own track instead of steering it;
     * a paused route is frozen on purpose and a plan derives speed from its steps.
     */
    val acceptsHandSteering: Boolean get() = this == HAND

    /** True when the run derives its own motion (or is deliberately held still). */
    val isAutomatic: Boolean get() = this == ROUTE || this == ROUTE_PAUSED || this == PLAN

    /** True when pausing this run is a meaningful action. */
    val canPauseRoute: Boolean get() = this == ROUTE

    /** True when a frozen route can be let go again. */
    val canResumeRoute: Boolean get() = this == ROUTE_PAUSED

    companion object {
        /**
         * Which input owns a run described by these stored facts.
         *
         * The plan is parsed rather than merely checked for text: the hook side falls
         * back to the flat speed when [SpeedPlan.parse] returns null, so a malformed
         * plan must not leave the app believing the plan is in charge.
         */
        fun of(active: Boolean, onRoute: Boolean, paused: Boolean, plan: String): MotionOwner = when {
            !active -> NONE
            onRoute && paused -> ROUTE_PAUSED
            onRoute -> ROUTE
            // A pace curve is a prescription too, and the hook derives the position from it.
            // Asking only the timed parser reported HAND for a run nobody could steer.
            SpeedPlan.parse(plan) != null || PacePlan.parse(plan) != null -> PLAN
            else -> HAND
        }
    }
}
