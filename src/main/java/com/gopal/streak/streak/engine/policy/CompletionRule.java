package com.gopal.streak.streak.engine.policy;

import java.time.LocalDate;

/**
 * Classifies how a completed day relates to the last one that counted.
 *
 * <p>Nothing here decides what the streak becomes — it only names the situation, so the engine can
 * switch on a verdict instead of on a tangle of date arithmetic.
 */
public final class CompletionRule {

    /** How a completed date sits relative to {@code lastCompleted}. */
    public enum Verdict {
        /** Nothing has ever been completed. */
        FIRST_EVER,
        /** The day right after the last completed one. */
        CONSECUTIVE,
        /** The same day that was already completed — a retry or a second proof. */
        SAME_DAY,
        /** Later than the last completed day, but with at least one day missed in between. */
        GAP,
        /** Earlier than the last completed day: history arriving late. */
        BACKDATED
    }

    public Verdict classify(LocalDate lastCompleted, LocalDate completed) {
        if (lastCompleted == null) {
            return Verdict.FIRST_EVER;
        }
        if (completed.isEqual(lastCompleted)) {
            return Verdict.SAME_DAY;
        }
        if (completed.isBefore(lastCompleted)) {
            return Verdict.BACKDATED;
        }
        return completed.isEqual(lastCompleted.plusDays(1)) ? Verdict.CONSECUTIVE : Verdict.GAP;
    }
}
