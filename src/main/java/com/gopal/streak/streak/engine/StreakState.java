package com.gopal.streak.streak.engine;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An immutable snapshot of one user's streak.
 *
 * <p>Pure data with no framework, no persistence and no clock: every instant or date it holds was
 * handed to it by a caller. That is what lets the engine be tested exhaustively in milliseconds.
 *
 * @param current          days in the running streak; 0 when broken or never started
 * @param longest          best streak ever reached; never decreases
 * @param freezesLeft      freezes available in the current month
 * @param lastCompleted    last local date that counted, or {@code null} before the first completion
 * @param brokenAt         when the streak broke, or {@code null} if it is not currently broken
 * @param valueBeforeBreak {@code current} at the moment of the break, or {@code null} if not broken
 */
public record StreakState(
        int current,
        int longest,
        int freezesLeft,
        LocalDate lastCompleted,
        Instant brokenAt,
        Integer valueBeforeBreak) {

    public StreakState {
        if (current < 0) {
            throw new IllegalArgumentException("current must not be negative: " + current);
        }
        if (freezesLeft < 0) {
            throw new IllegalArgumentException("freezesLeft must not be negative: " + freezesLeft);
        }
        if (longest < current) {
            throw new IllegalArgumentException(
                    "longest (" + longest + ") must be at least current (" + current + ")");
        }
        // brokenAt and valueBeforeBreak describe one event and are only meaningful together.
        if ((brokenAt == null) != (valueBeforeBreak == null)) {
            throw new IllegalArgumentException(
                    "brokenAt and valueBeforeBreak must both be set or both be null");
        }
    }

    /** A user who has never completed a day, starting with a full allowance of freezes. */
    public static StreakState initial(int freezes) {
        return new StreakState(0, 0, freezes, null, null, null);
    }

    /** True while a break is still on record — the precondition for a repair. */
    public boolean isBroken() {
        return brokenAt != null;
    }

    // --- transitions -------------------------------------------------------------------------
    // Mechanical only: each one moves fields, none of them decides *whether* it should happen.
    // That judgement belongs to the engine and its policies.

    /**
     * Records {@code date} as completed with the given streak length. Any outstanding break is
     * cleared: once a new day is banked, the old break is history and can no longer be repaired.
     */
    public StreakState completed(LocalDate date, int newCurrent) {
        return new StreakState(
                newCurrent, Math.max(longest, newCurrent), freezesLeft, date, null, null);
    }

    /** Spends one freeze to hold the streak through a missed day. */
    public StreakState freezeUsed() {
        return new StreakState(
                current, longest, freezesLeft - 1, lastCompleted, brokenAt, valueBeforeBreak);
    }

    /** Breaks the streak, remembering its length so a repair can restore it. */
    public StreakState broken(Instant at) {
        return new StreakState(0, longest, freezesLeft, lastCompleted, at, current);
    }

    /** Restores the streak to its pre-break length and forgets the break. */
    public StreakState repaired() {
        int restored = valueBeforeBreak == null ? current : valueBeforeBreak;
        return new StreakState(
                restored, Math.max(longest, restored), freezesLeft, lastCompleted, null, null);
    }

    /**
     * Tops the allowance up to {@code cap}. Deliberately a ceiling rather than an addition, so a
     * refill job that runs twice cannot hand out double.
     */
    public StreakState freezesRefilledTo(int cap) {
        return new StreakState(
                current, longest, Math.max(freezesLeft, cap), lastCompleted, brokenAt,
                valueBeforeBreak);
    }
}
