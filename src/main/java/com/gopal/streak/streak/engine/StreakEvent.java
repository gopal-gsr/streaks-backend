package com.gopal.streak.streak.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Something that happened to a user's streak, as told to the engine.
 *
 * <p>Sealed on purpose: the engine switches over these, so adding a sixth kind makes the compiler
 * point at every place that has to handle it.
 *
 * <p>Every event carries its own time. Nothing in this package ever asks the system what time it
 * is — the caller resolved the user's local date and the instant before constructing the event.
 */
public sealed interface StreakEvent {

    /**
     * A day's target was met.
     *
     * @param date the user's <em>local</em> date that was completed
     * @param at   when the completing proof arrived, used only for auditing
     */
    record DayCompleted(LocalDate date, Instant at) implements StreakEvent {
        public DayCompleted {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * A day's deadline passed with the target unmet. Emitted by the sweep job, one per missed day.
     *
     * @param date the user's local date that was missed
     * @param at   the deadline instant that passed; becomes {@code brokenAt} if the streak breaks
     */
    record DayMissed(LocalDate date, Instant at) implements StreakEvent {
        public DayMissed {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(at, "at");
        }
    }

    /**
     * A configured rest day. Distinct from {@link DayMissed}: a rest day neither advances the
     * streak nor threatens it, and costs no freeze.
     */
    record RestDay(LocalDate date) implements StreakEvent {
        public RestDay {
            Objects.requireNonNull(date, "date");
        }
    }

    /**
     * The user asked to undo a break.
     *
     * @param now the instant of the request, measured against {@code brokenAt} to apply the window
     */
    record RepairRequested(Instant now) implements StreakEvent {
        public RepairRequested {
            Objects.requireNonNull(now, "now");
        }
    }

    /**
     * The monthly allowance is due.
     *
     * @param amount the allowance to top up <em>to</em>, not to add — see
     *               {@link StreakState#freezesRefilledTo(int)}
     */
    record FreezesRefilled(int amount) implements StreakEvent {
        public FreezesRefilled {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must not be negative: " + amount);
            }
        }
    }
}
