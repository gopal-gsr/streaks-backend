package com.gopal.streak.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Every timezone calculation in the system, in one class.
 *
 * <p>Scattering this logic across services is the single most reliable way to break streaks for
 * travellers, for anyone in a DST zone, and for everyone near midnight. One class, one set of tests.
 *
 * <p>The only source of "now" is the injected {@link Clock}; nothing else here reads the system
 * time, which is what lets the awkward days be tested by simply typing them in.
 */
public final class UserClock {

    private final Clock clock;

    public UserClock() {
        this(Clock.systemUTC());
    }

    public UserClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    /** The user's plain calendar date at a given instant. */
    public LocalDate localDate(UserTime user, Instant at) {
        return at.atZone(user.zone()).toLocalDate();
    }

    /**
     * The streak day an instant belongs to, which is not always the calendar date.
     *
     * <p>With a deadline of 03:00, someone still working at 01:00 on Thursday is finishing
     * Wednesday's day, and their proof counts for Wednesday. Only when the local clock passes the
     * deadline hour does a new streak day begin.
     */
    public LocalDate streakDate(UserTime user, Instant at) {
        ZonedDateTime local = at.atZone(user.zone());
        return local.getHour() < user.deadlineHour()
                ? local.toLocalDate().minusDays(1)
                : local.toLocalDate();
    }

    /**
     * The instant at which the given streak day closes.
     *
     * <p>Day D ends at the deadline hour on D+1, so a deadline of midnight closes the day at 24:00
     * rather than at its start, and proof at 23:59:58 still counts.
     *
     * <p>DST-safe by construction: resolving through {@link ZonedDateTime} shifts a deadline that
     * falls in a spring-forward gap to the first valid instant after it, and picks the earlier of
     * the two offsets when a fall-back makes the hour ambiguous. Neither case throws.
     */
    public Instant deadlineInstant(UserTime user, LocalDate streakDay) {
        return streakDay.plusDays(1)
                .atTime(user.deadlineHour(), 0)
                .atZone(user.zone())
                .toInstant();
    }

    /** The first deadline strictly after {@code from}. */
    public Instant nextDeadline(UserTime user, Instant from) {
        LocalDate day = streakDate(user, from);
        Instant deadline = deadlineInstant(user, day);
        return deadline.isAfter(from) ? deadline : deadlineInstant(user, day.plusDays(1));
    }
}
