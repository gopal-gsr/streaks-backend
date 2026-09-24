package com.gopal.streak.streak.engine;

import com.gopal.streak.streak.engine.StreakEvent.DayCompleted;
import com.gopal.streak.streak.engine.StreakEvent.DayMissed;
import com.gopal.streak.streak.engine.StreakEvent.FreezesRefilled;
import com.gopal.streak.streak.engine.StreakEvent.RepairRequested;
import com.gopal.streak.streak.engine.StreakEvent.RestDay;
import com.gopal.streak.streak.engine.policy.CompletionRule;
import com.gopal.streak.streak.engine.policy.FreezePolicy;
import com.gopal.streak.streak.engine.policy.RepairPolicy;

/**
 * The single place a streak is allowed to change.
 *
 * <p>Pure: no Spring, no persistence, no clock. Given a state and an event it returns what happened,
 * always, for every input — so the whole rulebook can be tested in milliseconds without a database.
 *
 * <p>Instances are immutable and safe to share. This class does <em>not</em> handle concurrency:
 * two threads applying an event to the same stored state will both compute correctly and one will
 * lose. Serialising that is the caller's job (see §9.3 — row lock on the streak).
 */
public final class StreakEngine {

    private final CompletionRule completion;
    private final FreezePolicy freeze;
    private final RepairPolicy repair;

    public StreakEngine() {
        this(new CompletionRule(), new FreezePolicy(), new RepairPolicy());
    }

    public StreakEngine(CompletionRule completion, FreezePolicy freeze, RepairPolicy repair) {
        this.completion = completion;
        this.freeze = freeze;
        this.repair = repair;
    }

    /** The state a brand-new user starts from, with this engine's freeze allowance. */
    public StreakState initialState() {
        return StreakState.initial(freeze.monthlyAllowance());
    }

    public StreakOutcome apply(StreakState state, StreakEvent event) {
        return switch (event) {
            case DayCompleted e -> onCompleted(state, e);
            case DayMissed e -> onMissed(state, e);
            case RestDay e -> onRestDay(state, e);
            case RepairRequested e -> onRepairRequested(state, e);
            case FreezesRefilled e -> onFreezesRefilled(state, e);
        };
    }

    private StreakOutcome onCompleted(StreakState state, DayCompleted event) {
        return switch (completion.classify(state.lastCompleted(), event.date())) {
            // The most important rule in the system: GitHub retries, and a retry must not count.
            case SAME_DAY -> new StreakOutcome.Unchanged(state, "day already completed");
            // History arriving late never rewrites a streak that has already moved past it.
            case BACKDATED -> new StreakOutcome.Unchanged(state, "completion predates last completed day");
            case FIRST_EVER, GAP -> new StreakOutcome.Incremented(state.completed(event.date(), 1));
            case CONSECUTIVE ->
                    new StreakOutcome.Incremented(state.completed(event.date(), state.current() + 1));
        };
    }

    private StreakOutcome onMissed(StreakState state, DayMissed event) {
        // A second miss after a break must not overwrite brokenAt, which would silently extend the
        // repair window, nor valueBeforeBreak, which would make a repair restore zero.
        if (state.isBroken()) {
            return new StreakOutcome.Unchanged(state, "already broken");
        }
        // Nothing to protect yet, so a freeze would be spent for nothing.
        if (state.current() == 0) {
            return new StreakOutcome.Unchanged(state, "no active streak");
        }
        return freeze.canAbsorb(state)
                ? new StreakOutcome.FreezeUsed(state.freezeUsed())
                : new StreakOutcome.Broken(state.broken(event.at()));
    }

    private StreakOutcome onRestDay(StreakState state, RestDay event) {
        // A rest day must carry lastCompleted forward, or the next day reads as a two-day gap and
        // resets the streak to 1 — which would make rest days worse than useless. The count itself
        // does not move: a rest day preserves a streak, it does not earn one.
        if (completion.classify(state.lastCompleted(), event.date()) != CompletionRule.Verdict.CONSECUTIVE) {
            return new StreakOutcome.Unchanged(state, "rest day outside the active streak");
        }
        return new StreakOutcome.Unchanged(
                state.completed(event.date(), state.current()), "rest day");
    }

    private StreakOutcome onRepairRequested(StreakState state, RepairRequested event) {
        if (!state.isBroken()) {
            return new StreakOutcome.Rejected(state, "no break to repair");
        }
        if (!repair.canRepair(state, event.now())) {
            return new StreakOutcome.Rejected(state, "repair window of " + repair.window() + " has passed");
        }
        return new StreakOutcome.Repaired(state.repaired());
    }

    private StreakOutcome onFreezesRefilled(StreakState state, FreezesRefilled event) {
        // Unchanged is right even though freezesLeft moves: the streak itself did not.
        return new StreakOutcome.Unchanged(
                state.freezesRefilledTo(event.amount()), "freezes topped up to " + event.amount());
    }
}
