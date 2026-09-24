package com.gopal.streak.streak.engine.policy;

import com.gopal.streak.streak.engine.StreakState;

/**
 * Governs freezes: the allowance, and whether a missed day can be absorbed.
 *
 * <p><strong>Decision — one freeze buys one day.</strong> A three-day gap costs three freezes, so
 * two freezes cannot carry it and the streak breaks on the third day. The alternative, one freeze
 * per gap however long, would need the state to remember which gap a freeze was already spent on;
 * it holds no such field, and per-day is the more honest promise to make a user anyway.
 *
 * <p><strong>Decision — the allowance is a ceiling, not a balance.</strong> Refills top up to
 * {@link #monthlyAllowance()} rather than adding to it, so a refill job that runs twice cannot hand
 * out double. Unused freezes do not accumulate across months.
 */
public final class FreezePolicy {

    /** Free freezes per calendar month in v1. */
    public static final int DEFAULT_MONTHLY_ALLOWANCE = 2;

    private final int monthlyAllowance;

    public FreezePolicy() {
        this(DEFAULT_MONTHLY_ALLOWANCE);
    }

    public FreezePolicy(int monthlyAllowance) {
        if (monthlyAllowance < 0) {
            throw new IllegalArgumentException(
                    "monthlyAllowance must not be negative: " + monthlyAllowance);
        }
        this.monthlyAllowance = monthlyAllowance;
    }

    public int monthlyAllowance() {
        return monthlyAllowance;
    }

    /** Whether a missed day can be held by spending a freeze. */
    public boolean canAbsorb(StreakState state) {
        return state.freezesLeft() > 0;
    }
}
