---
name: git-flow
description: The branching, commit and PR workflow for the Streak backend. Use when starting work on a milestone, committing, opening a PR, or when unsure whether something belongs on main or a branch. Also use before any push, to run the pre-push checks.
---

# Git flow — Streak backend

One developer, one reviewer who is an AI, and a public repo. The workflow follows Gitflow, enforced
by GitHub branch protection on `main` and `develop` — not just convention. It exists to keep `main`
releasable, `develop` always integratable, and each commit explain itself in six months.

## Branching

`main` and `develop` are protected: no direct pushes, no force-pushes, no branch deletion. Nothing
lands on either that fails `./gradlew test`. Every change reaches them via PR, including a typo fix.

Two long-lived branches:

- `main` — releasable at every commit. Only receives merges from `release/*` or `hotfix/*`. Tag it
  on every merge (`vX.Y.Z`) once releases start happening.
- `develop` — integration branch. Everything lands here first.

Short-lived branches, named after the build order in `docs/BACKEND_DESIGN.md` §19 where one applies:

```
feature/6-schema-and-entities        a milestone or a slice of one, branches off develop
feature/github-webhook-hmac          ditto
fix/streak-repair-window-boundary    a defect found on develop, branches off develop
docs/backend-design-section-8        spec only, no code, branches off develop
release/0.1.0                        stabilizes develop for a release, branches off develop
hotfix/streak-repair-window-boundary urgent fix to something already shipped, branches off main
```

Flow:

- `feature/*`, `fix/*`, `docs/*` branch off `develop`, PR back into `develop`.
- `release/*` branches off `develop` once it's ready to ship. Only stabilization fixes land on it.
  PRs into **both** `main` and `develop` when done.
- `hotfix/*` branches off `main` for something broken in what's already shipped. PRs into **both**
  `main` and `develop` when done.

Lowercase, hyphens, no dates, no initials. Delete the branch once merged.

## Before every commit

1. `./gradlew test` — the whole suite, and it must be green. It takes seconds; there is no excuse.
2. `git status --short` — read it. Eclipse drops `bin/`, macOS drops `.DS_Store`, Gradle drops
   `build/`. All three are ignored; if one appears, fix `.gitignore` rather than committing it.
3. **Never commit a secret.** The repo is public. APNs keys, the GitHub OAuth client secret, the
   JWT signing key and the database password belong in environment variables (§15, §18.1). A secret
   pushed to a public repo is compromised even after a force-push removes it — it must be rotated.

## Commit messages

Subject: imperative mood, under 72 characters, no trailing period, no type prefix.

> `Add Flyway baseline and the identity tables`
> not `feat: added flyway migrations.`

Body: **why**, not what — the diff already says what. Worth a body whenever the change encodes a
decision, deviates from the design doc, or fixes something subtle. Reference doc sections by number
(§8.2) rather than by name.

If the change deviates from `docs/BACKEND_DESIGN.md`, say so in the body **and** record it in the
deviations table in `CLAUDE.md`. A deviation nobody wrote down becomes a bug report later.

No `Co-Authored-By` trailer, no other AI-attribution line. Commits read as the author's own work,
regardless of which tool drafted the diff.

## Pull requests

Every branch merges via PR — `main` and `develop` both reject direct pushes server-side, so there is
no "skip it for a docs fix" case anymore.

```bash
gh pr create --base develop --title "<same style as a commit subject>" --body "..."
```

Use `--base main` only from a `release/*` or `hotfix/*` branch. The body states what the milestone
required, what was built, what was tested, and anything deliberately left out. End it with:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

Merge with `--squash` when the branch has fix-up commits, `--merge` when each commit stands alone.
A `release/*` or `hotfix/*` PR needs two merges — into `main` and into `develop` — since it fixes
something on both.

## Pushing

Remote is SSH: `git@github.com:gopal-gsr/streaks-backend.git`, key `~/.ssh/id_ed25519_gsr`. PR
creation (`gh pr create`) needs a `gh` account with write access to the same repo — that is a
separate credential from the SSH push key, and read-only `gh` auth will 403 on PR/branch-protection
calls even though `git push` to a feature branch succeeds.

If a push fails with `Permission denied (publickey)`, check in this order: is the key loaded
(`ssh -T git@github.com` should greet **gopal-gsr**, not another account); is the key registered
under *Authentication keys* rather than *SSH signing keys*; is the private key passphrase-protected
with nothing in the agent.

`main` and `develop` reject direct pushes server-side (branch protection) — don't try to work around
that locally. Never `push --force` to either. On a short-lived branch you own, force-push is fine.

## What is not in this workflow

No release cadence yet — `release/*` and tagging exist as a path but nothing has shipped, so there's
no version number to cut until step 1 (Postgres, Flyway, `/health`, CI) lands. No CI status checks
required on the protected branches yet, because no CI exists; add `./gradlew test` as a required
check once §19 step 1 wires it up.
