package com.gopal.streak.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The edge cases from §6.4 of the design doc, one test each. */
class UserClockTest {

    private final UserClock clock = new UserClock();

    @Nested
    @DisplayName("daylight saving")
    class DaylightSaving {

        @Test
        @DisplayName("a deadline inside the spring-forward gap shifts to the first valid instant")
        void deadlineInsideTheSpringForwardGap() {
            // New York, 8 March 2026: 02:00 never happens, the clock jumps to 03:00.
            UserTime user = UserTime.of("America/New_York", 2);

            Instant deadline = clock.deadlineInstant(user, LocalDate.of(2026, 3, 7));

            // 03:00 EDT, not 02:00 — resolved forward, and no exception.
            assertThat(deadline).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
        }

        @Test
        @DisplayName("the 23-hour spring-forward day is still exactly one streak day")
        void springForwardIsOneDay() {
            UserTime user = UserTime.of("America/New_York", 0);

            Instant start = clock.deadlineInstant(user, LocalDate.of(2026, 3, 7));
            Instant end = clock.deadlineInstant(user, LocalDate.of(2026, 3, 8));

            assertThat(end.minusMillis(start.toEpochMilli()).toEpochMilli())
                    .isEqualTo(java.time.Duration.ofHours(23).toMillis());
            assertThat(clock.streakDate(user, start)).isEqualTo(LocalDate.of(2026, 3, 8));
        }

        @Test
        @DisplayName("an ambiguous fall-back deadline resolves to the earlier offset, consistently")
        void deadlineInsideTheFallBackOverlap() {
            // New York, 1 November 2026: 01:00 happens twice, once at -04:00 and once at -05:00.
            UserTime user = UserTime.of("America/New_York", 1);

            Instant deadline = clock.deadlineInstant(user, LocalDate.of(2026, 10, 31));

            // The first 01:00, still on EDT.
            assertThat(deadline).isEqualTo(Instant.parse("2026-11-01T05:00:00Z"));
        }

        @Test
        @DisplayName("the 25-hour fall-back day is still exactly one streak day")
        void fallBackIsOneDay() {
            UserTime user = UserTime.of("America/New_York", 0);

            Instant start = clock.deadlineInstant(user, LocalDate.of(2026, 10, 31));
            Instant end = clock.deadlineInstant(user, LocalDate.of(2026, 11, 1));

            assertThat(java.time.Duration.between(start, end)).isEqualTo(java.time.Duration.ofHours(25));
        }

        @Test
        @DisplayName("a midnight deadline inside a midnight gap still resolves")
        void midnightDeadlineInsideAMidnightGap() {
            // Sao Paulo, 4 November 2018: DST began at midnight, so 00:00 did not exist that day.
            UserTime user = UserTime.of("America/Sao_Paulo", 0);

            Instant deadline = clock.deadlineInstant(user, LocalDate.of(2018, 11, 3));

            assertThat(deadline).isEqualTo(Instant.parse("2018-11-04T03:00:00Z"));
        }
    }

    @Nested
    @DisplayName("the midnight boundary")
    class MidnightBoundary {

        private final UserTime kolkata = UserTime.of("Asia/Kolkata", 0);

        @Test
        @DisplayName("23:59:58 counts for that day")
        void justBeforeMidnight() {
            // 18:29:58Z is 23:59:58 in Kolkata (+05:30).
            Instant at = Instant.parse("2026-05-04T18:29:58Z");

            assertThat(clock.streakDate(kolkata, at)).isEqualTo(LocalDate.of(2026, 5, 4));
        }

        @Test
        @DisplayName("00:00:01 counts for the next day")
        void justAfterMidnight() {
            Instant at = Instant.parse("2026-05-04T18:30:01Z");

            assertThat(clock.streakDate(kolkata, at)).isEqualTo(LocalDate.of(2026, 5, 5));
        }
    }

    @Nested
    @DisplayName("a late deadline")
    class LateDeadline {

        private final UserTime nightOwl = UserTime.of("Asia/Kolkata", 3);

        @Test
        @DisplayName("01:00 still belongs to the previous day when the deadline is 03:00")
        void afterMidnightBelongsToYesterday() {
            // 19:30Z is 01:00 on 5 May in Kolkata.
            Instant at = Instant.parse("2026-05-04T19:30:00Z");

            assertThat(clock.streakDate(nightOwl, at)).isEqualTo(LocalDate.of(2026, 5, 4));
            assertThat(clock.localDate(nightOwl, at)).isEqualTo(LocalDate.of(2026, 5, 5));
        }

        @Test
        @DisplayName("04:00 has started the new day")
        void afterTheDeadlineIsANewDay() {
            Instant at = Instant.parse("2026-05-04T22:30:00Z");

            assertThat(clock.streakDate(nightOwl, at)).isEqualTo(LocalDate.of(2026, 5, 5));
        }

        @Test
        @DisplayName("the next deadline is always strictly in the future")
        void nextDeadlineIsAlwaysAhead() {
            Instant at = Instant.parse("2026-05-04T19:30:00Z");

            Instant next = clock.nextDeadline(nightOwl, at);

            assertThat(next).isAfter(at);
            // 03:00 on 5 May in Kolkata.
            assertThat(next).isEqualTo(Instant.parse("2026-05-04T21:30:00Z"));
        }
    }

    @Nested
    @DisplayName("moving between zones")
    class MovingBetweenZones {

        @Test
        @DisplayName("the same instant yields a different next deadline after a move")
        void flyingKolkataToNewYork() {
            Instant at = Instant.parse("2026-05-04T12:00:00Z");
            UserTime before = UserTime.of("Asia/Kolkata", 0);
            UserTime after = UserTime.of("America/New_York", 0);

            Instant kolkataDeadline = clock.nextDeadline(before, at);
            Instant newYorkDeadline = clock.nextDeadline(after, at);

            assertThat(kolkataDeadline).isEqualTo(Instant.parse("2026-05-04T18:30:00Z"));
            assertThat(newYorkDeadline).isEqualTo(Instant.parse("2026-05-05T04:00:00Z"));
            assertThat(newYorkDeadline).isAfter(kolkataDeadline);
        }
    }

    @Nested
    @DisplayName("the calendar")
    class Calendar {

        @Test
        @DisplayName("a leap day is an ordinary day")
        void leapDay() {
            UserTime user = UserTime.of("Asia/Kolkata", 0);

            // 29 February closes at midnight starting 1 March, which is 18:30Z on the 29th.
            Instant deadline = clock.deadlineInstant(user, LocalDate.of(2028, 2, 29));

            assertThat(deadline).isEqualTo(Instant.parse("2028-02-29T18:30:00Z"));
            assertThat(clock.streakDate(user, deadline.minusSeconds(1)))
                    .isEqualTo(LocalDate.of(2028, 2, 29));
            assertThat(clock.streakDate(user, Instant.parse("2028-02-28T18:30:00Z")))
                    .isEqualTo(LocalDate.of(2028, 2, 29));
        }
    }
}
