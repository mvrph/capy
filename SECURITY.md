# Security Policy

## Supported versions

**homebody** (capy) is a personal Android project. Only the current release is
maintained; older builds receive no fixes.

| Version | versionCode | Supported |
|---------|-------------|-----------|
| 1.1 (current) | 2 | ✓ |
| < 1.1 | ≤ 1 | ✗ |

Bump this table when `versionName` / `versionCode` in
[`src/app/build.gradle.kts`](src/app/build.gradle.kts) changes.

## Data and credentials

This is a single-user project with no public app-store release and no
multi-user accounts. It is **not** free of sensitive material, and this policy
does not claim otherwise:

- The app authenticates to a private **pulsar** telemetry backend with a bearer
  token. As of this policy the token is injected at build time via
  `BuildConfig` from a gitignored `keystore.properties` (or a `PULSAR_TOKEN` CI
  secret) and is no longer present in source.
- The Android **release keystore** and its passwords are likewise gitignored
  and kept only on the build machine.

> ⚠️ **Git history caveat.** Earlier commits committed the release keystore,
> its passwords, and the pulsar bearer token to this **public** repository.
> Removing them from the current tree does not un-leak them — those specific
> credentials must be treated as compromised and **rotated** (new pulsar token,
> regenerated signing key), independent of this policy.

## Reporting a vulnerability

If you find a security issue, please report it privately:

1. **Open a [GitHub Security Advisory](https://github.com/mvrph/capy/security/advisories/new)** — this keeps the report confidential until patched.
2. Alternatively, email the maintainer directly (see the GitHub profile for contact details).

Please include:
- A description of the vulnerability and its potential impact
- Steps to reproduce or a proof-of-concept
- Any suggested mitigation

**Response targets** (targets, not contractual SLAs, for a personal project):
acknowledge within **7 days**, and aim to publish a fix within **30 days** of
confirmation. Complex issues may take longer; you'll get a status update if so.
