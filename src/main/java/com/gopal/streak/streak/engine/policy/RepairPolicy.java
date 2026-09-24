package com.gopal.streak.streak.engine.policy;

import com.gopal.streak.streak.engine.StreakState;
import java.time.Duration;
import java.time.Instant;

/**
 * Governs repairs: how long after a break the streak can still be bought back.
 *
 * <p><strong>Decision — a repair is free and available once per break.</strong> It costs no freeze:
 * a freeze prevents a break, a repair undoes one, and charging for both would punish a single bad
 * day twice. "Once" needs no counter, because repairing clears the break from the state, so a
 * second request finds nothing to undo and is rejected.
 *
 * <p>The window is inclusive at its edge: a request at exactly 48h still repairs.
 */
public final class RepairPolicy {

    /** How long a break stays repairable in v1. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(48);

    private final Duration window;

    public RepairPolicy() {
        this(DEFAULT_WINDOW);
    }

    public RepairPolicy(Duration window) {
        if (window.isNegative()) {
            throw new IllegalArgumentException("window must not be negative: " + window);
        }
        this.window = window;
    }

    public Duration window() {
        return window;
    }

    /** Whether the streak is broken and the request still falls inside the window. */
    public boolean canRepair(StreakState state, Instant now) {
        if (!state.isBroken()) {
            return false;
        }
        return Duration.between(state.brokenAt(), now).compareTo(window) <= 0;
    }
}
