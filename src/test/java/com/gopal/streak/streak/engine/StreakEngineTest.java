package com.gopal.streak.streak.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopal.streak.streak.engine.StreakEvent.DayCompleted;
import com.gopal.streak.streak.engine.StreakEvent.DayMissed;
import com.gopal.streak.streak.engine.StreakEvent.FreezesRefilled;
import com.gopal.streak.streak.engine.StreakEvent.RepairRequested;
import com.gopal.streak.streak.engine.StreakEvent.RestDay;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rulebook from §8.3 of the design doc, case for case.
 *
 * <p>No clock, no database, no Spring — every date and instant here is a literal, which is why the
 * whole suite runs in milliseconds and why awkward days like a DST shift can simply be typed in.
 */
class StreakEngineTest {

    private static final Instant NOON = Instant.parse("2026-05-04T12:00:00Z");

    private final StreakEngine engine = new StreakEngine();

    /** A user mid-streak: {@code current} days, last completed on {@code lastCompleted}. */
    private static StreakState running(int current, LocalDate lastCompleted, int freezes) {
        return new StreakState(current, current, freezes, lastCompleted, null, null);
    }

    private static DayCompleted completed(LocalDate date) {
        return new DayCompleted(date, NOON);
    }

    @Nested
    @DisplayName("completing a day")
    class Completing {

        @Test
        @DisplayName("first ever completion starts the streak at 1")
        void firstEver() {
            StreakOutcome outcome = engine.apply(engine.initialState(), completed(LocalDate.of(2026, 5, 4)));

            assertThat(outcome).isInstanceOf(StreakOutcome.Incremented.class);
            assertThat(outcome.state().current()).isEqualTo(1);
            assertThat(outcome.state().longest()).isEqualTo(1);
        }

        @Test
        @DisplayName("two consecutive days make 2")
        void twoConsecutiveDays() {
            StreakState afterDay1 = engine.apply(engine.initialState(), completed(LocalDate.of(2026, 5, 4))).state();

            StreakOutcome outcome = engine.apply(afterDay1, completed(LocalDate.of(2026, 5, 5)));

            assertThat(outcome).isInstanceOf(StreakOutcome.Incremented.class);
            assertThat(outcome.state().current()).isEqualTo(2);
        }

        @Test
        @DisplayName("the same day twice still counts once — the webhook retry case")
        void sameDayTwiceIsIdempotent() {
            LocalDate day = LocalDate.of(2026, 5, 4);
            StreakState afterFirst = engine.apply(engine.initialState(), completed(day)).state();

            StreakOutcome outcome = engine.apply(afterFirst, completed(day));

            assertThat(outcome).isInstanceOf(StreakOutcome.Unchanged.class);
            assertThat(outcome.state().current()).isEqualTo(1);
        }

        @Test
        @DisplayName("the same day ten times over still counts once")
        void sameDayTenTimes() {
            LocalDate day = LocalDate.of(2026, 5, 4);
            StreakState state = engine.initialState();

            for (int i = 0; i < 10; i++) {
                state = engine.apply(state, completed(day)).state();
            }

            assertThat(state.current()).isEqualTo(1);
            assertThat(state.longest()).isEqualTo(1);
        }

        @Test
        @DisplayName("a gap of more than a day starts a fresh streak at 1")
        void gapStartsFresh() {
            StreakState state = running(7, LocalDate.of(2026, 5, 4), 2);

            StreakOutcome outcome = engine.apply(state, completed(LocalDate.of(2026, 5, 8)));

            assertThat(outcome).isInstanceOf(StreakOutcome.Incremented.class);
            assertThat(outcome.state().current()).isEqualTo(1);
            assertThat(outcome.state().longest()).isEqualTo(7);
        }

        @Test
        @DisplayName("a completion older than the last completed day changes nothing")
        void backdatedCompletionIsIgnored() {
            StreakState state = running(5, LocalDate.of(2026, 5, 4), 2);

            StreakOutcome outcome = engine.apply(state, completed(LocalDate.of(2026, 5, 1)));

            assertThat(outcome).isInstanceOf(StreakOutcome.Unchanged.class);
            assertThat(outcome.state()).isEqualTo(state);
        }
    }

    @Nested
    @DisplayName("missing a day")
    class Missing {

        @Test
        @DisplayName("a one-day gap with freezes in hand holds the streak and spends one")
        void oneDayGapWithFreezes() {
            StreakState state = running(5, LocalDate.of(2026, 5, 4), 2);

            StreakOutcome outcome = engine.apply(state, new DayMissed(LocalDate.of(2026, 5, 5), NOON));

            assertThat(outcome).isInstanceOf(StreakOutcome.FreezeUsed.class);
            assertThat(outcome.state().current()).isEqualTo(5);
            assertThat(outcome.state().freezesLeft()).isEqualTo(1);
        }

        @Test
        @DisplayName("a one-day gap with no freezes breaks the streak and records the break")
        void oneDayGapWithoutFreezes() {
            StreakState state = running(5, LocalDate.of(2026, 5, 4), 0);

            StreakOutcome outcome = engine.apply(state, new DayMissed(LocalDate.of(2026, 5, 5), NOON));

            assertThat(outcome).isInstanceOf(StreakOutcome.Broken.class);
            assertThat(outcome.state().current()).isZero();
            assertThat(outcome.state().brokenAt()).isEqualTo(NOON);
            assertThat(outcome.state().valueBeforeBreak()).isEqualTo(5);
            assertThat(outcome.state().longest()).isEqualTo(5);
        }

        @Test
        @DisplayName("two freezes cannot carry a three-day gap — one freeze buys one day")
        void threeDayGapOutlastsTwoFreezes() {
            StreakState state = running(5, LocalDate.of(2026, 5, 4), 2);

            for (int day = 5; day <= 7; day++) {
                state = engine.apply(state, new DayMissed(LocalDate.of(2026, 5, day), NOON)).state();
            }

            assertThat(state.current()).isZero();
            assertThat(state.freezesLeft()).isZero();
            assertThat(state.valueBeforeBreak()).isEqualTo(5);
        }

        @Test
        @DisplayName("further missed days after a break leave the break untouched")
        void missesAfterABreakChangeNothing() {
            StreakState broken =
                    engine.apply(running(5, LocalDate.of(2026, 5, 4), 0),
                            new DayMissed(LocalDate.of(2026, 5, 5), NOON)).state();

            StreakOutcome outcome =
                    engine.apply(broken, new DayMissed(LocalDate.of(2026, 5, 6), NOON.plus(Duration.ofDays(1))));

            assertThat(outcome).isInstanceOf(StreakOutcome.Unchanged.class);
            assertThat(outcome.state().brokenAt()).isEqualTo(NOON);
            assertThat(outcome.state().valueBeforeBreak()).isEqualTo(5);
        }

        @Test
        @DisplayName("a missed day with no streak yet spends no freeze")
        void missWithNoStreakSpendsNothing() {
            StreakOutcome outcome =
                    engine.apply(engine.initialState(), new DayMissed(LocalDate.of(2026, 5, 5), NOON));

            assertThat(outcome).isInstanceOf(StreakOutcome.Unchanged.class);
            assertThat(outcome.state().freezesLeft()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("rest days")
    class RestDays {

        @Test
        @DisplayName("a rest day preserves the streak without advancing it, and the next day continues")
        void restDayInTheMiddle() {
            StreakState state = running(3, LocalDate.of(2026, 5, 4), 2);

            StreakOutcome rest = engine.apply(state, new RestDay(LocalDate.of(2026, 5, 5)));
            assertThat(rest).isInstanceOf(StreakOutcome.Unchanged.class);
            assertThat(rest.state().current()).isEqualTo(3);

            StreakOutcome next = engine.apply(rest.state(), completed(LocalDate.of(2026, 5, 6)));
            assertThat(next).isInstanceOf(StreakOutcome.Incremented.class);
            assertThat(next.state().current()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("repair")
    class Repair {

        private StreakState brokenAt(Instant when) {
            return engine.apply(running(9, LocalDate.of(2026, 5, 4), 0),
                    new DayMissed(LocalDate.of(2026, 5, 5), when)).state();
        }

        @Test
        @DisplayName("a repair at 47h59m restores the streak")
        void repairJustInsideTheWindow() {
            StreakState broken = brokenAt(NOON);

            StreakOutcome outcome =
                    engine.apply(broken, new RepairRequested(NOON.plus(Duration.ofHours(47).plusMinutes(59))));

            assertThat(outcome).isInstanceOf(StreakOutcome.Repaired.class);
            assertThat(outcome.state().current()).isEqualTo(9);
            assertThat(outcome.state().isBroken()).isFalse();
        }

        @Test
        @DisplayName("a repair at 48h01m is refused")
        void repairJustOutsideTheWindow() {
            StreakState broken = brokenAt(NOON);

            StreakOutcome outcome =
                    engine.apply(broken, new RepairRequested(NOON.plus(Duration.ofHours(48).plusMinutes(1))));

            assertThat(outcome).isInstanceOf(StreakOutcome.Rejected.class);
            assertThat(outcome.state().current()).isZero();
        }

        @Test
        @DisplayName("a repair with nothing broken is refused")
        void repairWithoutABreak() {
            StreakOutcome outcome =
                    engine.apply(running(4, LocalDate.of(2026, 5, 4), 2), new RepairRequested(NOON));

            assertThat(outcome).isInstanceOf(StreakOutcome.Rejected.class);
            assertThat(outcome.state().current()).isEqualTo(4);
        }

        @Test
        @DisplayName("a second repair of the same break is refused")
        void repairTwice() {
            StreakState repaired =
                    engine.apply(brokenAt(NOON), new RepairRequested(NOON.plus(Duration.ofHours(1)))).state();

            StreakOutcome outcome =
                    engine.apply(repaired, new RepairRequested(NOON.plus(Duration.ofHours(2))));

            assertThat(outcome).isInstanceOf(StreakOutcome.Rejected.class);
            assertThat(outcome.state().current()).isEqualTo(9);
        }
    }

    @Nested
    @DisplayName("longest and freezes")
    class LongestAndFreezes {

        @Test
        @DisplayName("longest survives a break and never decreases")
        void longestNeverDecreases() {
            StreakState state = running(10, LocalDate.of(2026, 5, 4), 0);

            StreakOutcome broken = engine.apply(state, new DayMissed(LocalDate.of(2026, 5, 5), NOON));
            assertThat(broken.state().longest()).isEqualTo(10);

            StreakOutcome restarted = engine.apply(broken.state(), completed(LocalDate.of(2026, 5, 9)));
            assertThat(restarted.state().current()).isEqualTo(1);
            assertThat(restarted.state().longest()).isEqualTo(10);
        }

        @Test
        @DisplayName("a month rollover tops freezes back up to the allowance")
        void monthRolloverRefillsFreezes() {
            StreakState spent = running(5, LocalDate.of(2026, 5, 31), 0);

            StreakOutcome outcome = engine.apply(spent, new FreezesRefilled(2));

            assertThat(outcome.state().freezesLeft()).isEqualTo(2);
            assertThat(outcome.state().current()).isEqualTo(5);
        }

        @Test
        @DisplayName("a refill that runs twice does not hand out double")
        void refillIsIdempotent() {
            StreakState state = running(5, LocalDate.of(2026, 5, 31), 0);

            state = engine.apply(state, new FreezesRefilled(2)).state();
            state = engine.apply(state, new FreezesRefilled(2)).state();

            assertThat(state.freezesLeft()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("awkward days")
    class AwkwardDays {

        @Test
        @DisplayName("the 23-hour spring-forward day is still exactly one day")
        void springForward() {
            // US DST 2026 begins Sunday 8 March: that local day is 23 hours long.
            StreakState state = running(4, LocalDate.of(2026, 3, 8), 2);

            StreakOutcome outcome = engine.apply(state, completed(LocalDate.of(2026, 3, 9)));

            assertThat(outcome.state().current()).isEqualTo(5);
        }

        @Test
        @DisplayName("the 25-hour fall-back day is still exactly one day")
        void fallBack() {
            // US DST 2026 ends Sunday 1 November: that local day is 25 hours long.
            StreakState state = running(4, LocalDate.of(2026, 11, 1), 2);

            StreakOutcome outcome = engine.apply(state, completed(LocalDate.of(2026, 11, 2)));

            assertThat(outcome.state().current()).isEqualTo(5);
        }

        @Test
        @DisplayName("31 December carries into 1 January")
        void yearBoundary() {
            StreakState state = running(12, LocalDate.of(2026, 12, 31), 2);

            StreakOutcome outcome = engine.apply(state, completed(LocalDate.of(2027, 1, 1)));

            assertThat(outcome.state().current()).isEqualTo(13);
        }

        @Test
        @DisplayName("28 February carries into the 29th in a leap year")
        void leapDay() {
            StreakState state = running(6, LocalDate.of(2028, 2, 28), 2);

            StreakOutcome outcome = engine.apply(state, completed(LocalDate.of(2028, 2, 29)));

            assertThat(outcome.state().current()).isEqualTo(7);
        }
    }
}
