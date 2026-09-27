-- §5.1 Identity
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
