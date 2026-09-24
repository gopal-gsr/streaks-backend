# Streak — backend

Habit-streak backend for an iOS app. The client displays; this service decides.

**Source of truth:** `docs/BACKEND_DESIGN.md`. Read the relevant section before changing behaviour,
and update it when an implementation deviates — a stale spec is worse than none.

## Stack

Java 21 · Spring Boot 4.1.1 · Gradle (Kotlin DSL) · PostgreSQL 16 (not wired yet)

```bash
./gradlew test          # whole suite, ~2s
./gradlew compileJava
```

## Layering

`controller → service → domain → repository`. The domain layer has **zero framework dependencies**:
no Spring, no JPA, no clock. That is what keeps the streak rules testable in milliseconds.

- `streak/engine/` — pure rules. `StreakEngine.apply(state, event) → outcome`
- `streak/engine/policy/` — the tunable numbers: freezes, repair window, day classification
- `common/time/` — **all** timezone maths, in `UserClock`. Nowhere else.

## Rules that must not be broken

1. **The backend is the only writer of streak state.** The client never computes it.
2. **Never call `Instant.now()` or `LocalDate.now()` in domain code.** Time arrives as a parameter,
   or through the `Clock` injected into `UserClock`. This is what makes the DST tests possible.
3. **Day boundaries resolve in the user's IANA zone**, never UTC, never a fixed offset.
4. **Proof ingestion is idempotent.** GitHub retries; the same commit must never count twice.
5. **Changing a user's timezone never rewrites the past.** Only future evaluation moves.
6. `StreakEngine` does no locking. Serialising concurrent writes is the caller's job (§9.3).

## Decisions locked (2026-09-24)

- **One freeze buys one day.** A three-day gap costs three freezes. The state holds no field that
  could remember a freeze already spent on a given gap, so per-gap was never implementable.
- **Repair is free, within 48h, once per break.** "Once" needs no counter: repairing clears the
  break, so a second request finds nothing to undo. The window is inclusive at exactly 48h.
- **Freezes are a ceiling of 2, not a balance.** Refills use `max(left, cap)` so a job that runs
  twice cannot hand out double. They do not accumulate across months.

## Deviations from the design doc

| Doc | Here | Why |
|---|---|---|
| §6.2 `deadlineInstant` closes day D at hour H on D | Closes on D+1 | Otherwise a midnight deadline closes the day at its own start, and §6.4's "23:59:58 counts for that day" fails |
| §6.2 has only `localDate` | Added `streakDate` | §6.4's 03:00 deadline needs the day a proof counts toward, distinct from the calendar date |
| §6.2 `UserClock` is a `@Component` taking `User` | Plain class taking `UserTime` | No entity or Spring context exists yet; wire both at milestone 6 |
| §8.2 `RestDay` leaves state untouched | Carries `lastCompleted` forward | Otherwise the next day reads as a gap and resets the streak — rest days would destroy what they protect |
| §3 says Spring Boot 3.x | 4.1.1 | Initializr default; greenfield, so no reason to start on the superseded line |

## Where the build is

Build order (§19): steps **2 and 3 done** — `StreakEngine` and `UserClock`, 36 tests green.

Next: step 1 proper (Postgres, Flyway, `/health`, CI), then 4 (webhook spike) and 5 (APNs spike).
Nothing is wired to Spring yet; `spring-boot-starter` and its test starter are the only dependencies.
