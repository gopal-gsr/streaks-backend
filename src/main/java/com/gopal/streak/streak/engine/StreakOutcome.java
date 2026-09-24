package com.gopal.streak.streak.engine;

import java.util.Objects;

/**
 * What the engine did with an event, and the state it left behind.
 *
 * <p>The engine returns this rather than a bare {@link StreakState} because the caller needs to
 * know <em>what happened</em>, not just where things ended up: a broken streak and an unchanged one
 * can look identical in the numbers but call for completely different pushes. Being sealed, the
 * compiler forces every caller to answer for all six cases.
 */
public sealed interface StreakOutcome {

    /** The streak as it stands after the event, whatever the engine decided. */
    StreakState state();

    /** The day counted and the streak grew. */
    record Incremented(StreakState state) implements StreakOutcome {
        public Incremented {
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * The event was legitimate but moved nothing — a duplicate webhook, a backdated proof, a rest
     * day. Distinct from {@link Rejected}: nothing went wrong here.
     *
     * @param reason why nothing moved; for logs, not for users
     */
    record Unchanged(StreakState state, String reason) implements StreakOutcome {
        public Unchanged {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** A missed day was absorbed by a freeze; the streak held and the allowance dropped by one. */
    record FreezeUsed(StreakState state) implements StreakOutcome {
        public FreezeUsed {
            Objects.requireNonNull(state, "state");
        }
    }

    /** A missed day with no freeze left. {@code current} is 0 and the break is on record. */
    record Broken(StreakState state) implements StreakOutcome {
        public Broken {
            Objects.requireNonNull(state, "state");
        }
    }

    /** A break was undone within the window; the streak is back at its previous length. */
    record Repaired(StreakState state) implements StreakOutcome {
        public Repaired {
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * The event was refused — a repair with no break to undo, or one past the window.
     *
     * @param reason why it was refused; this one is worth surfacing to the user
     */
    record Rejected(StreakState state, String reason) implements StreakOutcome {
        public Rejected {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
