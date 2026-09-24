---
name: git-flow
description: The branching, commit and PR workflow for the Streak backend. Use when starting work on a milestone, committing, opening a PR, or when unsure whether something belongs on main or a branch. Also use before any push, to run the pre-push checks.
---

# Git flow — Streak backend

One developer, one reviewer who is an AI, and a public repo. The workflow is therefore light: it
exists to keep `main` releasable and to make each commit explain itself in six months.

## Branching

`main` is always green. Nothing lands on it that fails `./gradlew test`.

Work on a branch whenever the change spans more than one sitting or touches more than one package.
A typo fix or a doc tweak can go straight to `main`.

Branch names follow the build order in `docs/BACKEND_DESIGN.md` §19, because that is how the work is
actually sequenced:

```
milestone/6-schema-and-entities      a numbered milestone from §19
feature/github-webhook-hmac          a slice of one
fix/streak-repair-window-boundary    a defect
docs/backend-design-section-8        spec only, no code
```

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

End with:

```
Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
```

## Pull requests

Open one for anything a reviewer would want to read as a unit — a whole milestone, or a change to
the streak rules. Skip it for a docs fix.

```bash
gh pr create --title "<same style as a commit subject>" --body "..."
```

The body states what the milestone required, what was built, what was tested, and anything
deliberately left out. End it with:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

Merge with `--squash` when the branch has fix-up commits, `--merge` when each commit stands alone.

## Pushing

Remote is SSH: `git@github.com:gopal-gsr/streaks-backend.git`, key `~/.ssh/id_ed25519_gsr`.

If a push fails with `Permission denied (publickey)`, check in this order: is the key loaded
(`ssh -T git@github.com` should greet **gopal-gsr**, not another account); is the key registered
under *Authentication keys* rather than *SSH signing keys*; is the private key passphrase-protected
with nothing in the agent.

Never `push --force` to `main`. On a branch it is fine.

## What is not in this workflow

No `develop` branch, no release branches, no tags yet. A solo project on a service that isn't
deployed does not need them. Revisit when there is a running production instance to protect.
