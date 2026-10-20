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

    /** A straight-line run whose speed comes from an interval plan. */
    PLAN,

    /** A straight-line run steered by hand. */
    HAND,
    ;

    /**
     * True when the floating joystick may write speed and heading.
     *
     * Only [HAND] qualifies. Writing a speed during a [ROUTE] or [PLAN] run does
     * not steer anything: both derive distance from the clock, so the subject
     * would move along its own track by the difference instead.
     */
    val acceptsHandSteering: Boolean get() = this == HAND

    /** True when the run derives its own motion and the hand controls are inert. */
    val isAutomatic: Boolean get() = this == ROUTE || this == PLAN

    companion object {
        /**
         * Which input owns a run described by these stored facts.
         *
         * The plan is parsed rather than merely checked for text: the hook side falls
         * back to the flat speed when [SpeedPlan.parse] returns null, so a malformed
         * plan must not leave the app believing the plan is in charge.
         */
        fun of(active: Boolean, onRoute: Boolean, plan: String): MotionOwner = when {
            !active -> NONE
            onRoute -> ROUTE
            SpeedPlan.parse(plan) != null -> PLAN
            else -> HAND
        }
    }
}
