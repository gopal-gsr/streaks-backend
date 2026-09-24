# Streak — Backend System Design

> **Scope: backend only.** The iOS app is treated as an untrusted client at the API boundary.
> Stack: **Java 21 · Spring Boot 3.x · PostgreSQL 16 · Gradle**

**Tags:** **[v1]** build now · **[v1.1]** next · **[v2]** later.

---

## Table of Contents

1. [Responsibilities & non-goals](#1-responsibilities--non-goals)
2. [Architecture](#2-architecture)
3. [Tech stack](#3-tech-stack)
4. [Package structure](#4-package-structure)
5. [Data model](#5-data-model)
6. [Time & timezone handling](#6-time--timezone-handling) ★
7. [Proof ingestion pipeline](#7-proof-ingestion-pipeline) ★
8. [The streak engine](#8-the-streak-engine) ★
9. [Concurrency & idempotency](#9-concurrency--idempotency) ★
10. [Scheduled jobs](#10-scheduled-jobs)
11. [Authentication & authorization](#11-authentication--authorization)
12. [Notification service](#12-notification-service)
13. [REST API](#13-rest-api)
14. [Error contract](#14-error-contract)
15. [Security](#15-security)
16. [Testing strategy](#16-testing-strategy)
17. [Observability](#17-observability)
18. [Configuration & deployment](#18-configuration--deployment)
19. [Build order](#19-build-order)
20. [Open decisions](#20-open-decisions)

---

## 1. Responsibilities & non-goals

### Owns

| # | Responsibility |
|---|---|
| 1 | Identity — verify Apple/Google tokens, issue and refresh own JWTs |
| 2 | Proof connections — GitHub OAuth, webhook lifecycle, encrypted token storage |
| 3 | Proof ingestion — receive webhooks and client claims, deduplicate, persist |
| 4 | Day evaluation — resolve the user's **local** date, aggregate progress, decide completion |
| 5 | **Streak state** — the single source of truth: increment, freeze, break, repair |
| 6 | Push notifications — silent unlock signals, reminders, streak events |
| 7 | Scheduled work — deadline sweeps, reminders, freeze refills |
| 8 | REST API for the client |

### Explicitly does NOT own

| Not ours | Why |
|---|---|
| The blocked-app list | Apple `ApplicationToken`s are opaque and cannot leave the device |
| Applying/removing the shield | Device-local only |
| AI roadmap generation | Runs on-device; backend only persists the result |
| Reading HealthKit / Screen Time | Device-only APIs; the client reports claims |

### Core invariants

1. **The backend is the only writer of streak state.** The client displays; it never computes.
2. **Every day boundary is computed in the user's IANA timezone**, never UTC, never a fixed offset.
3. **Proof ingestion is idempotent.** GitHub retries; the same commit must never count twice.
4. **Verified proof and client-attested proof are distinct trust tiers** and are stored as such.

---

## 2. Architecture

```
                         ┌──────────────────────────────────┐
   iOS client ──HTTPS──► │      REST API (Spring MVC)       │
                         │   JWT filter · rate limit · CORS │
                         └───────────────┬──────────────────┘
                                         │
   GitHub ────webhook───► ┌──────────────▼──────────────────┐
                          │       APPLICATION SERVICES       │
                          ├──────────────────────────────────┤
                          │ AuthService                      │
                          │ ConnectionService  (GitHub OAuth)│
                          │ WebhookReceiver    ──► async     │
                          │ ProofIngestionService            │
                          │ DayEvaluator                     │
                          │ StreakService                    │
                          │ NotificationService              │
                          │ AchievementService      [v1.1]   │
                          │ FriendStreakService     [v1.1]   │
                          └──────┬───────────────────┬───────┘
                                 │                   │
              ┌──────────────────▼───────┐   ┌───────▼──────────────┐
              │   DOMAIN (pure Java)     │   │   SCHEDULER          │
              │  ★ StreakEngine          │   │  deadline sweep      │
              │    DayCompletionRule     │   │  reminders           │
              │    FreezePolicy          │   │  freeze refill       │
              │    RepairPolicy          │   │  friend streak pass  │
              │  no Spring, no DB, no    │   └──────────────────────┘
              │  clock — pure functions  │
              └──────────────────────────┘
                                 │
              ┌──────────────────▼──────────────────────────────┐
              │        PERSISTENCE (Spring Data JPA)            │
              └──────────────────┬──────────────────────────────┘
                                 │
        ┌────────────────────────▼─────────┐   ┌─────────────────┐
        │          PostgreSQL              │   │  APNs (Pushy)   │
        └──────────────────────────────────┘   └─────────────────┘
        ┌──────────────────────────────────────────────────────┐
        │  External: GitHub API · Apple JWKS · Google JWKS      │
        └──────────────────────────────────────────────────────┘
```

**Layering rule:** `controller → service → domain → repository`.
The **domain layer has zero framework dependencies** — that is what makes the streak logic testable
in milliseconds without a database.

---

## 3. Tech stack

| Concern | Choice | Notes |
|---|---|---|
| Language | **Java 21 (LTS)** | Records for DTOs, sealed interfaces, pattern matching |
| Framework | **Spring Boot 3.x** | Web MVC, not WebFlux — simpler, adequate |
| Build | **Gradle (Kotlin DSL)** | |
| DB | **PostgreSQL 16** | |
| ORM | Spring Data JPA + Hibernate | |
| **Migrations** | **Flyway** | Versioned, never edited after merge |
| Auth | Spring Security (resource server) | |
| JWT | **Nimbus JOSE + JWT** | Also used to verify Apple/Google tokens |
| Google token verify | `google-api-client` → `GoogleIdTokenVerifier` | |
| **APNs** | **Pushy** (`com.eatthepath:pushy`) | HTTP/2, token-based auth |
| GitHub API | `org.kohsuke:github-api` or `RestClient` | |
| Scheduling | Spring `@Scheduled` + **ShedLock** | ShedLock prevents double-runs if you ever scale to 2 instances |
| Validation | Jakarta Bean Validation | |
| API docs | **springdoc-openapi** | Generates the contract the iOS side builds against |
| Tests | JUnit 5 · Mockito · **Testcontainers** | |
| Quality | Spotless · Error Prone · Jacoco | |
| Observability | Actuator · Micrometer · Logback JSON · Sentry | |

---

## 4. Package structure

```
com.gopal.streak
├── StreakApplication.java
│
├── config/
│   ├── SecurityConfig.java          JwtConfig.java
│   ├── ApnsConfig.java              SchedulerConfig.java
│   └── OpenApiConfig.java           CryptoConfig.java
│
├── auth/
│   ├── AuthController.java
│   ├── AuthService.java
│   ├── verifier/  AppleTokenVerifier · GoogleTokenVerifier · IdentityTokenVerifier
│   ├── jwt/       JwtIssuer · JwtAuthFilter · RefreshTokenService
│   └── dto/
│
├── user/          UserController · UserService · User · UserRepository
├── device/        DeviceController · DeviceService · Device · DeviceRepository
│
├── connection/
│   ├── ConnectionController · ConnectionService
│   └── github/  GitHubOAuthClient · GitHubApiClient · WebhookRegistrar
│
├── habit/         HabitController · HabitService · Habit · HabitRepository
├── roadmap/       RoadmapController · RoadmapService · Roadmap · RoadmapNode
│
├── proof/
│   ├── WebhookController.java        ← receives, validates, returns 202 fast
│   ├── ProofIngestionService.java    ← async processing
│   ├── DayEvaluator.java
│   ├── ProofEvent.java · DayLog.java
│   └── source/  GitHubProofHandler · ClientClaimHandler · ProofHandler (iface)
│
├── streak/
│   ├── StreakController · StreakService · Streak · StreakRepair
│   └── engine/                       ★ PURE JAVA — no Spring, no JPA
│       ├── StreakEngine.java
│       ├── StreakState.java          (record)
│       ├── StreakEvent.java          (sealed interface)
│       └── policy/  FreezePolicy · RepairPolicy · CompletionRule
│
├── notification/  NotificationService · ApnsSender · PayloadBuilder · NotificationLog
├── scheduler/     DeadlineSweepJob · ReminderJob · FreezeRefillJob · FriendStreakJob
│
├── social/        [v1.1]  Friendship · FriendStreak · FriendService
├── achievement/   [v1.1]  AchievementService · Milestone · MonthlyBadge
│
└── common/
    ├── error/     ApiException · GlobalExceptionHandler · ErrorCode (enum)
    ├── crypto/    TokenEncryptor  (AES-GCM)
    ├── time/      UserClock  ← all timezone maths lives here, nowhere else
    └── util/
```

> **`common/time/UserClock` is deliberately a single class.** Timezone logic scattered across
> services is the #1 source of bugs in an app like this. One class, one set of tests.

---

## 5. Data model

### 5.1 Identity

```sql
CREATE TABLE users (
    id              UUID PRIMARY KEY,
    handle          TEXT UNIQUE,                       -- [v2] public profile
    display_name    TEXT,
    email           TEXT,
    timezone        TEXT NOT NULL,                     -- IANA: "Asia/Kolkata"
    deadline_hour   SMALLINT NOT NULL DEFAULT 23,      -- 0-23, local
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE TABLE auth_identities (
    provider        TEXT NOT NULL,                     -- APPLE | GOOGLE | PHONE[v2]
    subject         TEXT NOT NULL,                     -- provider's stable id
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, subject)
);
CREATE INDEX idx_auth_user ON auth_identities(user_id);

CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL UNIQUE,              -- store the HASH, never the token
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    apns_token      TEXT NOT NULL UNIQUE,
    platform        TEXT NOT NULL DEFAULT 'IOS',
    environment     TEXT NOT NULL DEFAULT 'PRODUCTION',  -- SANDBOX for dev builds
    last_seen_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_devices_user ON devices(user_id);
```

### 5.2 Proof connections

```sql
CREATE TABLE proof_connections (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type            TEXT NOT NULL,                     -- GITHUB | STRAVA[v2]
    access_token    BYTEA NOT NULL,                    -- AES-GCM encrypted
    refresh_token   BYTEA,
    external_id     TEXT,                              -- github login
    scopes          TEXT,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE | REVOKED | EXPIRED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, type)
);

CREATE TABLE github_repos (
    id              UUID PRIMARY KEY,
    connection_id   UUID NOT NULL REFERENCES proof_connections(id) ON DELETE CASCADE,
    github_repo_id  BIGINT NOT NULL,                   -- stable across renames
    owner           TEXT NOT NULL,
    name            TEXT NOT NULL,
    webhook_id      BIGINT,                            -- needed to delete it later
    webhook_secret  BYTEA NOT NULL,                    -- encrypted, per-repo
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (connection_id, github_repo_id)
);
CREATE INDEX idx_repos_github_id ON github_repos(github_repo_id);  -- webhook lookup path
```

> **Key on `github_repo_id`, not `owner/name`.** Repos get renamed and transferred; the numeric id
> doesn't change. Your webhook handler must resolve by id.

### 5.3 Habits & roadmap

```sql
CREATE TABLE habits (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    domain          TEXT NOT NULL,        -- CODING | GYM | YOGA | RUNNING | LEARNING | ...
    proof_type      TEXT NOT NULL,        -- GITHUB_COMMIT | HEALTHKIT_WORKOUT
                                          -- | SCREEN_TIME | TIMER | MANUAL
    proof_config    JSONB NOT NULL,       -- {"repoId":"…","minCommits":1}
    target_value    INT  NOT NULL DEFAULT 1,
    level           TEXT NOT NULL DEFAULT 'CASUAL',    -- CASUAL | REGULAR | SERIOUS
    rest_days       SMALLINT[] DEFAULT '{}',           -- [v1.1] 0=Sun
    active          BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_habits_user_active ON habits(user_id) WHERE active;

CREATE TABLE roadmaps (
    id              UUID PRIMARY KEY,
    habit_id        UUID NOT NULL REFERENCES habits(id) ON DELETE CASCADE,
    title           TEXT NOT NULL,
    total_days      INT  NOT NULL,
    generated_by    TEXT NOT NULL,        -- AI | TEMPLATE | MANUAL
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE roadmap_nodes (
    id              UUID PRIMARY KEY,
    roadmap_id      UUID NOT NULL REFERENCES roadmaps(id) ON DELETE CASCADE,
    day_index       INT  NOT NULL,
    title           TEXT NOT NULL,
    tasks           JSONB,
    state           TEXT NOT NULL DEFAULT 'LOCKED',    -- LOCKED|CURRENT|DONE|SKIPPED
    is_milestone    BOOLEAN NOT NULL DEFAULT false,
    completed_at    TIMESTAMPTZ,
    UNIQUE (roadmap_id, day_index)
);
```

### 5.4 Proof & streak ★

```sql
-- Audit log AND the idempotency guard.
CREATE TABLE proof_events (
    id              UUID PRIMARY KEY,
    habit_id        UUID NOT NULL REFERENCES habits(id) ON DELETE CASCADE,
    source          TEXT NOT NULL,        -- GITHUB_WEBHOOK | CLIENT_CLAIM
    trust_tier      TEXT NOT NULL,        -- VERIFIED | ATTESTED
    external_ref    TEXT NOT NULL,        -- commit SHA, workout UUID
    value           INT  NOT NULL DEFAULT 1,
    occurred_at     TIMESTAMPTZ NOT NULL, -- when the WORK happened
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload         JSONB,
    processed       BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT uq_proof UNIQUE (habit_id, source, external_ref)   -- ★ idempotency
);
CREATE INDEX idx_proof_unprocessed ON proof_events(processed, received_at)
    WHERE NOT processed;

CREATE TABLE day_logs (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    habit_id        UUID NOT NULL REFERENCES habits(id) ON DELETE CASCADE,
    local_date      DATE NOT NULL,        -- in the USER's timezone
    progress_value  INT  NOT NULL DEFAULT 0,
    target_value    INT  NOT NULL,        -- snapshot: target may change later
    completed       BOOLEAN NOT NULL DEFAULT false,
    completed_at    TIMESTAMPTZ,
    proof_type      TEXT,
    frozen          BOOLEAN NOT NULL DEFAULT false,
    is_rest_day     BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT uq_day UNIQUE (habit_id, local_date)   -- ★ one row per habit per day
);
CREATE INDEX idx_daylogs_user_date ON day_logs(user_id, local_date DESC);

CREATE TABLE streaks (
    id                UUID PRIMARY KEY,
    habit_id          UUID NOT NULL UNIQUE REFERENCES habits(id) ON DELETE CASCADE,
    current           INT NOT NULL DEFAULT 0,
    longest           INT NOT NULL DEFAULT 0,
    freezes_left      SMALLINT NOT NULL DEFAULT 2,
    freezes_refilled  DATE,
    last_completed    DATE,
    broken_at         TIMESTAMPTZ,        -- drives the repair window
    value_before_break INT,               -- what to restore on repair
    next_deadline_at  TIMESTAMPTZ,        -- ★ materialised; see §6.3
    version           BIGINT NOT NULL DEFAULT 0,   -- optimistic locking
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_streaks_deadline ON streaks(next_deadline_at);   -- ★ sweep query

CREATE TABLE streak_repairs (
    id              UUID PRIMARY KEY,
    habit_id        UUID NOT NULL REFERENCES habits(id) ON DELETE CASCADE,
    broken_date     DATE NOT NULL,
    restored_value  INT  NOT NULL,
    restored_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    cost            INT  NOT NULL DEFAULT 1
);
```

### 5.5 Social & gamification **[v1.1]**

```sql
CREATE TABLE friendships (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    friend_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status          TEXT NOT NULL,        -- PENDING | ACCEPTED | BLOCKED
    invite_code     TEXT UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (user_id <> friend_id),
    UNIQUE (user_id, friend_id)
);

CREATE TABLE friend_streaks (
    id              UUID PRIMARY KEY,
    user_a_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_b_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    current         INT NOT NULL DEFAULT 0,
    longest         INT NOT NULL DEFAULT 0,
    last_both_date  DATE,
    CHECK (user_a_id < user_b_id),        -- ★ canonical ordering prevents dup pairs
    UNIQUE (user_a_id, user_b_id)
);

CREATE TABLE achievements (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type            TEXT NOT NULL,
    tier            INT  NOT NULL DEFAULT 1,
    progress        INT  NOT NULL DEFAULT 0,
    unlocked_at     TIMESTAMPTZ,
    UNIQUE (user_id, type, tier)
);

CREATE TABLE monthly_badges (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    month           DATE NOT NULL,        -- first of month
    type            TEXT NOT NULL,
    earned_at       TIMESTAMPTZ,
    UNIQUE (user_id, month, type)
);
```

### 5.6 Notifications

```sql
CREATE TABLE notification_prefs (
    user_id           UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    reminders_enabled BOOLEAN NOT NULL DEFAULT true,
    reminder_lead_min INT NOT NULL DEFAULT 120,
    learned_hour      SMALLINT,           -- [v1.1] personalised timing
    quiet_start       SMALLINT,
    quiet_end         SMALLINT
);

CREATE TABLE notification_log (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type            TEXT NOT NULL,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    apns_id         TEXT,
    result          TEXT,                 -- SENT | REJECTED | ERROR
    detail          TEXT
);
CREATE INDEX idx_notiflog_user_time ON notification_log(user_id, sent_at DESC);
```

---

## 6. Time & timezone handling ★

**This is the hardest correct-by-design problem in the system.** Get it wrong and streaks break for
travellers, for anyone in a DST zone, and for everyone near midnight. All of it lives in
`common/time/UserClock`.

### 6.1 The rules

1. Store the user's **IANA zone id** (`Asia/Kolkata`), never a UTC offset. Offsets change; zones don't.
2. All timestamps in the DB are `TIMESTAMPTZ` (stored UTC).
3. `local_date` is a `DATE` **with no zone** — it is already resolved in the user's zone.
4. Never use `LocalDateTime.now()` or `new Date()` anywhere in business code. Inject a `Clock`.

### 6.2 The primitives

```java
@Component
public class UserClock {

    private final Clock clock;   // injected — Clock.systemUTC() in prod, fixed in tests

    /** The user's local calendar date for a given instant. */
    public LocalDate localDate(User user, Instant at) {
        return at.atZone(ZoneId.of(user.getTimezone())).toLocalDate();
    }

    /** The instant at which the given local day's deadline falls. DST-safe. */
    public Instant deadlineInstant(User user, LocalDate localDate) {
        ZoneId zone = ZoneId.of(user.getTimezone());
        return localDate.atTime(user.getDeadlineHour(), 0)
                        .atZone(zone)          // ZonedDateTime resolves DST gaps/overlaps
                        .toInstant();
    }

    /** The next deadline strictly after `from`. */
    public Instant nextDeadline(User user, Instant from) {
        LocalDate today = localDate(user, from);
        Instant todays = deadlineInstant(user, today);
        return todays.isAfter(from) ? todays
                                    : deadlineInstant(user, today.plusDays(1));
    }
}
```

### 6.3 ★ The sweep problem, and how to avoid it

**Naive approach:** every hour, loop all users, compute whether their deadline just passed.
That is a full table scan per hour and it gets worse with every signup.

**Correct approach:** materialise `streaks.next_deadline_at` and index it.

```sql
SELECT * FROM streaks
 WHERE next_deadline_at <= now()
 ORDER BY next_deadline_at
 LIMIT 500;
```

An index range scan, regardless of user count. After processing each row, recompute and store the
next deadline. Recompute also whenever the user changes their timezone or deadline hour.

### 6.4 The edge cases you must test

| Case | Expectation |
|---|---|
| **Spring-forward DST** (02:00 → 03:00; a 23-hour day) | Still exactly one day. Deadline resolves forward, never throws |
| **Fall-back DST** (01:00 happens twice; a 25-hour day) | One day. `ZonedDateTime` picks the earlier offset — fine, be consistent |
| **Deadline hour lands inside the DST gap** | `ZonedDateTime` shifts it forward automatically. Assert it |
| **User flies Kolkata → New York mid-streak** | Timezone update must recompute `next_deadline_at`; do **not** retroactively rewrite past `day_log` rows |
| **Commit at 23:59:58, deadline 00:00** | Counts for that day |
| **Commit at 00:00:01** | Counts for the *next* day |
| **User sets deadline to 03:00** | "Today" effectively runs to 3am — intentional, many people work past midnight |
| Leap day | Nothing special, but assert it |

> **Decision to lock in:** when a user changes timezone, the past is immutable. Only future
> evaluation moves. Anything else lets people farm streaks by hopping zones.

---

## 7. Proof ingestion pipeline ★

### 7.1 Two entry points, one pipeline

```
  GitHub webhook          Client claim (HealthKit / ScreenTime / Timer)
  trust: VERIFIED         trust: ATTESTED
         │                          │
         ▼                          ▼
   ┌──────────────────────────────────────┐
   │  Validate  (HMAC | JWT + rate limit) │
   └──────────────────┬───────────────────┘
                      ▼
   ┌──────────────────────────────────────┐
   │  Persist proof_event                 │  ← unique(habit,source,ref) = dedupe
   │  return 202 immediately              │     GitHub times out at ~10s
   └──────────────────┬───────────────────┘
                      ▼  (async)
   ┌──────────────────────────────────────┐
   │  ProofIngestionService.process()     │
   │    resolve habit → user              │
   │    local_date = UserClock.localDate  │
   │    upsert day_log, progress += value │
   │    completed = progress >= target    │
   └──────────────────┬───────────────────┘
                      ▼  (only on transition false→true)
   ┌──────────────────────────────────────┐
   │  StreakService.onDayCompleted()      │
   │  AchievementService.evaluate()       │
   │  NotificationService.sendUnlock()    │
   └──────────────────────────────────────┘
```

### 7.2 Why 202-then-async matters

GitHub expects a response in **~10 seconds** and will mark the webhook failed otherwise. If your
processing path touches the DB several times, sends a push, and evaluates achievements, you are one
slow query away from GitHub disabling your webhook.

**So: verify → persist raw → `202 Accepted` → process on a worker.**

For v1 the "worker" is `@Async` with a bounded pool plus the `idx_proof_unprocessed` index as a
safety net — a reconciliation job re-picks anything left `processed = false` after 5 minutes.
That is a poor-man's queue and it is entirely adequate at this scale. Move to a real queue only if
you outgrow it.

### 7.3 Webhook handler specifics

```java
@PostMapping("/webhooks/github")
public ResponseEntity<Void> receive(
        @RequestHeader("X-GitHub-Event") String event,
        @RequestHeader("X-GitHub-Delivery") String deliveryId,
        @RequestHeader("X-Hub-Signature-256") String signature,
        @RequestBody byte[] rawBody) {          // ★ raw bytes — HMAC is over the exact body

    if (!"push".equals(event)) return ResponseEntity.accepted().build();

    PushPayload payload = parse(rawBody);
    GithubRepo repo = repos.findByGithubRepoId(payload.repository().id())
                           .orElse(null);
    if (repo == null) return ResponseEntity.accepted().build();   // not ours; don't leak

    if (!hmac.verify(rawBody, signature, decrypt(repo.getWebhookSecret()))) {
        log.warn("bad signature repo={} delivery={}", repo.getId(), deliveryId);
        return ResponseEntity.status(401).build();
    }
    ingestion.enqueue(repo, payload, deliveryId);
    return ResponseEntity.accepted().build();
}
```

**Non-obvious requirements:**

- **HMAC over the raw byte body**, before any JSON parsing. Re-serialising changes bytes and breaks the signature.
- **Constant-time comparison** (`MessageDigest.isEqual`) — `String.equals` leaks timing.
- Resolve by **`github_repo_id`**, not `owner/name`.
- Return **202 for unknown repos** — a 404 tells an attacker which repos are registered.
- **Ignore:** non-push events, branch/tag deletions (`deleted: true`), zero-commit pushes, and
  optionally commits whose author isn't the linked user (force-push and merge noise).

### 7.4 Anti-gaming rules

| Rule | Reason |
|---|---|
| Commits must be to the **linked repo** | Otherwise a typo fix anywhere counts |
| Ignore empty commits (`--allow-empty`) | Trivial to script |
| Ignore pushes where `commits[]` is empty | Branch deletes, tag pushes |
| Use `occurred_at` = commit timestamp, but **clamp to `received_at`** | Git commit dates are client-supplied and trivially forged |
| One `day_log` per habit per day, progress capped at target | No banking ahead |

> **Design stance:** this is an accountability tool, not a prison. Cheap gaming is blocked; don't
> build forensics. The user is trying to help themselves.

---

## 8. The streak engine ★

The only piece where a bug silently destroys trust. **Pure Java — no Spring, no JPA, no `Instant.now()`.**

### 8.1 Shape

```java
public record StreakState(
        int current, int longest, int freezesLeft,
        LocalDate lastCompleted, Instant brokenAt, Integer valueBeforeBreak) {}

public sealed interface StreakEvent {
    record DayCompleted(LocalDate date, Instant at)          implements StreakEvent {}
    record DayMissed(LocalDate date, Instant at)             implements StreakEvent {}
    record RestDay(LocalDate date)                           implements StreakEvent {}
    record RepairRequested(Instant now)                      implements StreakEvent {}
    record FreezesRefilled(int amount)                       implements StreakEvent {}
}

public sealed interface StreakOutcome {
    record Incremented(StreakState state)                    implements StreakOutcome {}
    record Unchanged(StreakState state, String reason)       implements StreakOutcome {}
    record FreezeUsed(StreakState state)                     implements StreakOutcome {}
    record Broken(StreakState state)                         implements StreakOutcome {}
    record Repaired(StreakState state)                       implements StreakOutcome {}
    record Rejected(StreakState state, String reason)        implements StreakOutcome {}
}

public final class StreakEngine {
    private final FreezePolicy freeze;
    private final RepairPolicy repair;
    public StreakOutcome apply(StreakState s, StreakEvent e) { … }
}
```

Returning a **sealed `StreakOutcome`** rather than just the new state is deliberate: the caller
needs to know *what happened* to decide which push to send, and the compiler then forces you to
handle every case.

### 8.2 Rules

| Event | Condition | Outcome |
|---|---|---|
| `DayCompleted` | `lastCompleted == date` | **Unchanged** — idempotent, the most important rule |
| `DayCompleted` | `lastCompleted == date - 1` | **Incremented** `current + 1` |
| `DayCompleted` | `lastCompleted == null` | **Incremented** `current = 1` |
| `DayCompleted` | gap > 1 day | **Incremented** `current = 1` (fresh start) |
| `DayCompleted` | `date < lastCompleted` (backfill) | **Unchanged** — never rewrite history |
| `DayMissed` | `freezesLeft > 0` | **FreezeUsed** — `current` held, `freezesLeft - 1`, day marked frozen |
| `DayMissed` | `freezesLeft == 0` | **Broken** — `valueBeforeBreak = current`, `current = 0`, `brokenAt = now` |
| `RestDay` | configured rest day | **Unchanged** — neither advances nor breaks |
| `RepairRequested` | within 48h of `brokenAt` | **Repaired** — `current = valueBeforeBreak` |
| `RepairRequested` | after 48h, or not broken | **Rejected** |
| any increment | — | `longest = max(longest, current)` |

### 8.3 Required test cases

```
✓ first ever completion                        → current = 1
✓ two consecutive days                         → 2
✓ same day twice (webhook retry)               → still 1        ★ idempotency
✓ same day 10× concurrently                    → still 1
✓ one-day gap, 2 freezes                       → held, 1 freeze left
✓ one-day gap, 0 freezes                       → 0, brokenAt set
✓ three-day gap, 2 freezes                     → 0  (freezes cover ONE day only)
✓ rest day in the middle                       → streak preserved, not incremented
✓ backdated completion older than lastCompleted→ unchanged
✓ repair at 47h 59m                            → restored
✓ repair at 48h 01m                            → rejected
✓ repair when never broken                     → rejected
✓ repair twice                                 → second rejected
✓ longest updates only on increment            → never decreases
✓ month rollover                               → freezes refill to 2
✓ DST spring-forward day                       → exactly one day
✓ DST fall-back day                            → exactly one day
✓ year boundary Dec 31 → Jan 1                 → increments
✓ Feb 28 → Feb 29 (leap)                       → increments
```

> If you write nothing else with tests, write these. Everything about this product's credibility
> rests on the streak number being right.

---

## 9. Concurrency & idempotency ★

Three races exist. All are real, all will happen.

### 9.1 Duplicate webhook delivery

GitHub retries on timeout or 5xx. The same commit arrives twice.

**Defence:** `UNIQUE (habit_id, source, external_ref)` on `proof_events`.
The second insert throws `DataIntegrityViolationException` → catch it, log at debug, return 202.
This is a *database-level* guarantee — it holds even across instances.

### 9.2 Concurrent proof for the same day

Two commits pushed a second apart. Both transactions try to upsert the same `day_log`.

**Defence — atomic upsert, not read-modify-write:**

```sql
INSERT INTO day_logs (id, user_id, habit_id, local_date, progress_value, target_value, completed)
VALUES (:id, :userId, :habitId, :date, :value, :target, :value >= :target)
ON CONFLICT (habit_id, local_date) DO UPDATE
   SET progress_value = day_logs.progress_value + EXCLUDED.progress_value,
       completed      = (day_logs.progress_value + EXCLUDED.progress_value) >= day_logs.target_value,
       completed_at   = COALESCE(day_logs.completed_at,
                                 CASE WHEN (day_logs.progress_value + EXCLUDED.progress_value)
                                           >= day_logs.target_value
                                      THEN now() END)
RETURNING *, (xmax = 0) AS was_insert;
```

The `RETURNING` tells you the resulting state, so you can detect the **false → true transition**
and fire the streak update exactly once.

### 9.3 Concurrent streak mutation

Webhook ingestion and the deadline sweep could touch the same streak row simultaneously.

**Defence — pessimistic row lock, scoped per habit:**

```java
@Query("select s from Streak s where s.habitId = :habitId")
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Streak> findByHabitIdForUpdate(UUID habitId);
```

Contention is per-habit, so it is effectively zero. The `version` column gives you optimistic
locking as a second line of defence for read-mostly paths.

### 9.4 Transaction boundaries

| Operation | Boundary |
|---|---|
| Webhook receipt | Short tx: insert `proof_event` only, then commit and return 202 |
| Proof processing | One tx: lock streak → upsert day_log → apply engine → persist |
| Push sending | **Outside** the transaction — never hold a DB lock across a network call |
| Deadline sweep | One tx **per habit**, not one for the batch |

> **Rule:** no external I/O inside a transaction. Commit first, then push.

---

## 10. Scheduled jobs

All jobs wrapped in **ShedLock** so a second instance can never double-run them.

| Job | Cadence | Does |
|---|---|---|
| **DeadlineSweepJob** | every 5 min | `next_deadline_at <= now()` → incomplete? freeze or break → push → recompute next deadline |
| **ReminderJob** | every 15 min | Users `lead_minutes` before deadline, still incomplete, reminders on, outside quiet hours → nudge |
| **ProofReconciliationJob** | every 5 min | Re-process `proof_events` stuck `processed = false` > 5 min |
| **FreezeRefillJob** | daily 00:15 UTC | Refill freezes for anyone whose local month just rolled over |
| **FriendStreakJob** [v1.1] | daily | Advance/reset shared streaks after both deadlines pass |
| **TokenCleanupJob** | daily | Delete expired refresh tokens, prune `notification_log` > 90 days |
| **DeadDeviceJob** | daily | Remove devices APNs has rejected as unregistered |

### 10.1 Sweep, in detail

```java
@Scheduled(fixedDelay = 5 * 60_000)
@SchedulerLock(name = "deadlineSweep", lockAtMostFor = "4m")
public void sweep() {
    List<Streak> due = streaks.findDue(Instant.now(clock), PageRequest.of(0, 500));
    for (Streak s : due) {
        try {
            sweepOne(s.getHabitId());        // own transaction per habit
        } catch (Exception e) {
            log.error("sweep failed habit={}", s.getHabitId(), e);
            // continue — one bad habit must not stop the batch
        }
    }
}
```

**Requirements:**
- Batch with a limit, and page — never `findAll()`.
- **One transaction per habit.** A failure must not roll back the whole sweep.
- Idempotent: if the sweep runs twice for the same day, the second is a no-op (the engine's
  `Unchanged` path covers it).
- Recompute `next_deadline_at` at the end of each habit, always — even on failure paths, or the
  row will be picked up forever in a hot loop.

### 10.2 Reminder timing **[v1.1]**

Track `completed_at` per day. After ~14 completions, compute the median local hour and store it as
`learned_hour`. Send reminders relative to that rather than a fixed offset — "you usually commit
around 7pm, it's 8pm" lands far better than a generic nag.

---

## 11. Authentication & authorization

### 11.1 Sign-in flow

```
Client: native Sign in with Apple / Google → identity token (JWT)
   │
   ▼
POST /v1/auth/apple { identityToken, nonce }
   │
   ├─ fetch Apple JWKS (cached ~24h, refresh on unknown kid)
   ├─ verify RS256 signature
   ├─ verify iss  == https://appleid.apple.com
   ├─ verify aud  == your bundle id            ★ prevents token reuse from another app
   ├─ verify exp / iat
   ├─ verify nonce matches what the client sent ★ prevents replay
   │
   ├─ find auth_identity(APPLE, sub)
   │     found     → load user
   │     not found → create user + auth_identity
   │
   └─ issue: access JWT (15 min) + refresh token (30 days, rotating)
```

Google is identical against Google's JWKS with `aud` = your OAuth client id.

### 11.2 Token policy

| Token | Lifetime | Storage |
|---|---|---|
| Access JWT | 15 min | Client memory / Keychain. Stateless — never in the DB |
| Refresh token | 30 days, **rotating** | Only the **hash** is stored. Rotate on every use |
| GitHub access token | per GitHub | AES-GCM encrypted at rest |

**Refresh rotation:** each use issues a new refresh token and revokes the old one. If a revoked
token is presented, treat it as theft — revoke the whole family and force re-auth.

### 11.3 Authorization

Every resource is user-scoped. Enforce it in the **repository query**, not with a post-fetch check:

```java
// ✅ correct — cannot leak
habitRepository.findByIdAndUserId(habitId, principal.userId())
               .orElseThrow(() -> new ApiException(HABIT_NOT_FOUND));

// ❌ wrong — an IDOR waiting to happen
Habit h = habitRepository.findById(habitId).orElseThrow();
if (!h.getUserId().equals(principal.userId())) throw …;
```

**Return 404, not 403**, for resources belonging to another user — 403 confirms the id exists.

### 11.4 Apple private-relay caveat

Sign in with Apple may return `abc@privaterelay.appleid.com`. So:

- **Never** treat email as the identity key. `(provider, subject)` is the key.
- A user who signs in with Google and later Apple **will create two accounts**.
- **v1 decision:** accept it. Offer "link another sign-in method" in settings at [v2].

---

## 12. Notification service

### 12.1 Types

| Type | Push kind | Trigger | Payload |
|---|---|---|---|
| `proof_verified` | **silent** (`content-available: 1`) | Day completed | `{ date, streak, shouldUnshield: true }` |
| `reminder` | alert | Before deadline, incomplete | `{ minutesLeft, streak }` |
| `freeze_used` | alert | Freeze consumed | `{ freezesLeft }` |
| `streak_broken` | alert | Broken | `{ lostValue, repairUntil }` |
| `milestone` [v1.1] | alert | 7/30/100/365 | `{ type, value }` |
| `friend_at_risk` [v1.1] | alert | Friend hasn't done theirs | `{ friendName }` |

### 12.2 The silent push is load-bearing

`proof_verified` is what lets the phone unlock **without the user opening the app**. It must be:

- `content-available: 1`, **no alert body** (an alert would wake the user for nothing)
- priority `5` (silent pushes must not be priority 10 — APNs will throttle or reject)
- `apns-push-type: background`

⚠️ **iOS may still throttle or drop background pushes.** Never treat delivery as guaranteed:
the client must also re-check `/today` on launch and on foreground. The push is an optimisation,
not the contract.

### 12.3 Sending

```java
ApnsClient client = new ApnsClientBuilder()
    .setApnsServer(ApnsClientBuilder.PRODUCTION_APNS_HOST)
    .setSigningKey(ApnsSigningKey.loadFromInputStream(p8, teamId, keyId))
    .setConcurrentConnections(4)
    .build();
```

**Handling responses:**

| APNs response | Action |
|---|---|
| Accepted | Log `apns_id` |
| `BadDeviceToken` / `Unregistered` | **Delete the device row** — it will never work again |
| `TooManyRequests` | Back off with jitter |
| `PayloadTooLarge` | Bug — assert payload < 4 KB |
| 5xx | Retry with exponential backoff, max 3 |

Send **after** the transaction commits. A push about a state that then rolls back is worse than no push.

---

## 13. REST API

Base `/v1`. All authed with `Authorization: Bearer <jwt>` except `/auth/*` and `/webhooks/*`.

### 13.1 Auth
```
POST   /auth/apple        { identityToken, nonce }   → 200 { accessToken, refreshToken, user }
POST   /auth/google       { idToken }                → 200 { … }
POST   /auth/refresh      { refreshToken }           → 200 { accessToken, refreshToken }
POST   /auth/logout       { refreshToken }           → 204
DELETE /auth/account                                 → 204   (GDPR delete)
```

### 13.2 User & devices
```
GET    /me                                → 200 { id, displayName, timezone, deadlineHour, … }
PATCH  /me                { timezone?, deadlineHour?, displayName? }   → 200
POST   /devices           { apnsToken, platform, environment }         → 201
DELETE /devices/{id}                                                   → 204
GET    /me/notification-prefs         PATCH /me/notification-prefs
```

### 13.3 Connections
```
POST   /connections/github/start                → 200 { deviceCode, userCode, verificationUri, interval }
POST   /connections/github/poll  { deviceCode } → 200 { connectionId } | 428 authorization_pending
GET    /connections                             → 200 [ { id, type, externalId, status } ]
DELETE /connections/{id}                        → 204  (also deletes the webhooks)
GET    /connections/github/repos                → 200 [ { id, owner, name, private } ]
POST   /repos             { githubRepoId }      → 201 { id }   (registers the webhook)
DELETE /repos/{id}                              → 204  (removes the webhook)
```

### 13.4 Habits & roadmap
```
POST   /habits    { domain, proofType, proofConfig, targetValue, level }  → 201
GET    /habits                                                            → 200 [ … ]
PATCH  /habits/{id}  { targetValue?, level?, restDays?, active? }         → 200
DELETE /habits/{id}                                                       → 204

POST   /habits/{id}/roadmap  { title, totalDays, days: [ { title, tasks[] } ] }  → 201
GET    /habits/{id}/roadmap                     → 200 { title, totalDays, currentDay, nodes[] }
PATCH  /roadmap/nodes/{id}   { state }          → 200
```

### 13.5 The daily loop ★
```
GET    /today?habitId=                          → 200   ← the client's primary call
GET    /streak?habitId=                         → 200 { current, longest, freezesLeft, brokenAt, repairUntil }
GET    /history?habitId=&from=&to=              → 200 [ { localDate, completed, progress, frozen } ]
POST   /proof/claim  { habitId, type, value, externalRef, occurredAt }   → 202
POST   /streak/repair { habitId }               → 200 { restored } | 409 REPAIR_WINDOW_EXPIRED
```

**`GET /today` response:**
```json
{
  "localDate": "2026-09-24",
  "habitId": "7f3a…",
  "target": 3,
  "progress": 3,
  "completed": true,
  "completedAt": "2026-09-24T18:42:11Z",
  "deadlineAt": "2026-09-24T17:30:00Z",
  "isRestDay": false,
  "shouldUnshield": true,
  "streak": { "current": 12, "longest": 30, "freezesLeft": 2 },
  "roadmapNode": { "dayIndex": 12, "title": "Binary search trees", "tasks": ["…"] }
}
```

> `shouldUnshield` is computed server-side deliberately. The client must not re-derive unlock
> policy from raw fields — one place to change the rule.

### 13.6 Social **[v1.1]**
```
POST   /friends/invite            → 201 { inviteCode, expiresAt }
POST   /friends/accept  { code }  → 200
GET    /friends                   → 200 [ { user, sharedStreak, completedToday } ]
DELETE /friends/{id}              → 204
GET    /achievements              → 200 [ … ]
```

### 13.7 Webhooks
```
POST   /webhooks/github           → 202 always (401 only on bad signature)
```

### 13.8 Ops
```
GET    /actuator/health           → liveness/readiness
GET    /actuator/metrics
GET    /v3/api-docs  ·  /swagger-ui.html
```

---

## 14. Error contract

One shape, everywhere:

```json
{
  "error": {
    "code": "REPAIR_WINDOW_EXPIRED",
    "message": "This streak can no longer be restored.",
    "traceId": "8f2c1a…",
    "details": { "brokenAt": "2026-09-20T18:00:00Z", "windowHours": 48 }
  }
}
```

| HTTP | When |
|---|---|
| 400 | Validation failure |
| 401 | Missing/invalid/expired JWT, bad webhook signature |
| 403 | Authenticated but not permitted |
| 404 | Not found **or belongs to another user** |
| 409 | State conflict — `REPO_ALREADY_LINKED`, `REPAIR_WINDOW_EXPIRED` |
| 422 | Semantically invalid — e.g. roadmap with 0 days |
| 428 | `authorization_pending` on GitHub device-flow polling |
| 429 | Rate limited |
| 500 | Unexpected — log with `traceId`, never leak a stack trace |

`ErrorCode` is an **enum**, not free text, so the client can branch on it and you can't typo it.
Central `@RestControllerAdvice` maps exceptions to this shape — no try/catch in controllers.

---

## 15. Security

| Concern | Control |
|---|---|
| OAuth tokens at rest | **AES-GCM**, key from env/KMS, never in source or logs |
| Webhook secrets | Per-repo, random 32 bytes, encrypted |
| Webhook authenticity | HMAC-SHA256 over raw body, **constant-time compare** |
| Identity tokens | Verify signature + `iss` + `aud` + `exp` + nonce against provider JWKS |
| Access JWT | 15 min, `HS256`/`RS256`, `kid` in header for rotation |
| Refresh tokens | Hashed at rest, rotating, family revocation on reuse |
| IDOR | Scope every query by `user_id` in the repository method |
| Rate limits | `/auth/*` per IP · `/proof/claim` per user · webhook per repo |
| SQL injection | JPA/parameterised only — no string-built SQL |
| PII in logs | No emails, tokens, or commit contents. Log ids |
| Dependency CVEs | OWASP dependency-check in CI |
| TLS | HTTPS only, HSTS |
| GDPR delete | `DELETE /auth/account` cascades; remove GitHub webhooks first |

### 15.1 Encryption helper

```java
@Component
public class TokenEncryptor {
    private final SecretKey key;                 // from env, base64, 256-bit
    public byte[] encrypt(String plaintext) { … }   // AES/GCM/NoPadding, random 12-byte IV
    public String decrypt(byte[] ciphertext) { … }  // IV prefixed to ciphertext
}
```

Never `AES/ECB`. Never a fixed IV. Prefix the IV to the ciphertext and store the pair.

---

## 16. Testing strategy

```
        ╱╲
       ╱  ╲        E2E  (5-10)        Testcontainers + MockWebServer
      ╱────╲                          full webhook → push flow
     ╱      ╲
    ╱  INTEG ╲     (40-60)            @SpringBootTest + Testcontainers Postgres
   ╱──────────╲                       repositories, controllers, schedulers
  ╱            ╲
 ╱     UNIT     ╲  (200+)             pure JUnit, no Spring context
╱────────────────╲                    ★ StreakEngine, UserClock, policies
```

| Layer | Tools | Covers |
|---|---|---|
| **Unit** | JUnit 5, AssertJ | `StreakEngine` (all §8.3 cases), `UserClock` (all §6.4 cases), HMAC verifier, policies |
| **Integration** | Testcontainers Postgres | Repositories, upsert concurrency, Flyway migrations apply cleanly |
| **Web** | MockMvc | Auth filter, error contract, validation, IDOR — *assert 404 for another user's resource* |
| **External** | MockWebServer / WireMock | GitHub API, Apple/Google JWKS |
| **Scheduler** | Fixed `Clock` bean | Sweep at exact instants, DST days |

**Non-negotiables:**
- `Clock` is **injected everywhere**. A test that needs `Thread.sleep` is a design failure.
- Concurrency test: fire 10 parallel proof events for the same day, assert `current == 1`.
- Migration test: Flyway applies from empty on every build.

---

## 17. Observability

**Metrics (Micrometer):**
```
proof.received{source,trust}        proof.duplicate{source}
day.completed                       streak.incremented / .broken / .frozen / .repaired
push.sent{type,result}              webhook.signature.invalid
sweep.duration                      sweep.habits.processed
```

**Alerts worth having:**
- `webhook.signature.invalid` spike → misconfiguration or attack
- `sweep.duration` approaching the lock timeout
- push rejection rate > 5%
- any `proof_event` unprocessed > 15 min

**Logging:** structured JSON, one `traceId` per request propagated into async processing.
**Never** log tokens, emails, or commit messages.

---

## 18. Configuration & deployment

### 18.1 Environment
```
DATABASE_URL / DB_USERNAME / DB_PASSWORD
JWT_SIGNING_KEY                 (256-bit, base64)
TOKEN_ENCRYPTION_KEY            (256-bit, base64)
APNS_P8_BASE64 / APNS_KEY_ID / APNS_TEAM_ID / APNS_BUNDLE_ID / APNS_ENVIRONMENT
GITHUB_CLIENT_ID / GITHUB_CLIENT_SECRET
APPLE_BUNDLE_ID                 (aud check)
GOOGLE_CLIENT_ID                (aud check)
PUBLIC_BASE_URL                 (webhook callback)
SENTRY_DSN
```

### 18.2 Pipeline
```
push → GitHub Actions
         ├─ ./gradlew build          (unit + integration, Testcontainers)
         ├─ spotless / Error Prone / OWASP dependency-check
         ├─ docker build (multi-stage, JRE 21 slim, non-root)
         └─ deploy → Railway ──► Flyway migrates on boot
```

### 18.3 Runtime notes

| Concern | Setting |
|---|---|
| Memory | ~512 MB. `-XX:MaxRAMPercentage=75` |
| Startup | Enable Spring AOT if cold starts bite |
| Pool | Hikari max 10 (Railway Postgres free tier is small) |
| Health | Actuator `/health/readiness` gates traffic |
| Migrations | Flyway on boot; **never** `ddl-auto: update` |
| Scaling | Single instance for v1. ShedLock already makes 2 safe |

---

## 19. Build order

| # | Milestone | Done when | Blocks |
|---|---|---|---|
| 1 | Skeleton: Boot + Postgres + Flyway + `/health` + CI | Deploys green | everything |
| 2 | **`StreakEngine` + all §8.3 tests** | Every case passes | nothing — do it first, needs no infra |
| 3 | **`UserClock` + all §6.4 tests** | DST cases pass | — |
| 4 | Webhook spike: ngrok, receive, verify HMAC | Real commit logs a payload | proof pipeline |
| 5 | APNs spike via Pushy | Your phone buzzes | notifications |
| 6 | Schema + entities + repositories | Migrations apply clean | — |
| 7 | Auth: Apple + Google + JWT + refresh | Client gets a token | all authed routes |
| 8 | GitHub OAuth + webhook registration | Repo links, hook appears on GitHub | ingestion |
| 9 | Proof ingestion + DayEvaluator + upsert | Commit marks the day complete | streak |
| 10 | Wire `StreakService` → engine, with locking | Streak increments once, never twice | — |
| 11 | `GET /today` + `/streak` + `/history` | Client can render | client work |
| 12 | Scheduler: sweep, reminders, refill | Missed day breaks correctly | — |
| 13 | Notifications for all §12.1 types | Silent unlock works end to end | **product works** |
| 14 | [v1.1] friends, achievements, HealthKit claims | — | — |

> **Steps 2 and 3 first.** They are pure logic, need no database, no Apple approval, no GitHub —
> and they are the two places a bug is invisible and fatal. You can finish both in an evening.

---

## 20. Open decisions

- [ ] Repair window — 48h? Cost: a freeze, or a separate credit?
- [ ] Freezes: 2/month free, or earned? *(v1: free)*
- [ ] Multiple active habits per user in v1? *(schema allows; recommend UI ships one)*
- [ ] Rest days in v1 or v1.1?
- [ ] Apple private-relay duplicate accounts — accept in v1, or build linking now?
- [ ] Do we cap `progress_value` at target, or record overflow? *(recommend record, display capped)*
- [ ] `occurred_at` clamping — reject commits dated > 24h in the past?
- [ ] Data retention for `proof_events` — prune after 1 year?
- [ ] Account deletion: hard delete, or soft + 30-day grace?
