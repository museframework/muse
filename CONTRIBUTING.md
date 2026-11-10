# Contributing to Muse

Muse uses a lightweight GitFlow model with two long-lived branches. For technical verification commands and module ownership, see [Contributor verification](docs/development/verification.md). For the version and publication process, see [Releasing Muse](docs/development/releasing.md).

## Branches and pull requests

| Branch | Role | Maven project version |
| --- | --- | --- |
| `main` | Stable, release-ready source and release tags | Non-SNAPSHOT (for example `0.1.0`) |
| `develop` | Integration branch for the next release | Next `-SNAPSHOT` (initially `0.2.0-SNAPSHOT`) |
| `feature/*`, `fix/*`, `chore/*` | Short-lived work started from `develop` | Inherit the development snapshot |
| `release/<version>` | Temporary release stabilization branch started from `develop` | Exact non-SNAPSHOT target version |
| `hotfix/*` | Urgent released-version fixes started from `main` | Next appropriate patch version |

1. Branch from the current `develop` for ordinary code, tests, and documentation changes.
2. Submit a pull request targeting `develop`; keep changes scoped and run the relevant verification before requesting review.
3. Require a passing Maven CI run and review before merging. The existing workflow runs on pushes and PRs for both long-lived branches; configure branch rules to enforce reviews and required checks.
4. Keep `main` protected and release-ready. Do not push feature work or snapshot versions directly to `main`.
5. Prefer short-lived branches; delete them after their changes have been merged.

Repository-wide policy documentation that applies immediately to the stable branch may be proposed directly to `main` in a documentation-only PR. Such changes must subsequently be brought into `develop` so the branches do not diverge unintentionally.

## Releases and hotfixes

Use `release/<version>` only when preparing an actual release. Stabilize and verify there, merge via PR to `main`, and tag the exact `main` HEAD. Transfer release-only fixes back to `develop` without resetting its next-SNAPSHOT version. Then advance `develop` to the following snapshot.

For urgent production fixes, create `hotfix/*` from `main`, prepare a patch-version release, and bring the fix back to `develop`. Never merge a stable version bump into `develop` without checking its active snapshot version.

Release tag format is `v<major>.<minor>.<patch>`; the tag's stripped version must exactly match the Maven project version. Do not tag or publish from `develop`. See [Releasing Muse](docs/development/releasing.md) for CI and Maven Central requirements.

## Automation and agent guidance

Agents must inspect the target branch and current repository instructions before editing; they must not assume a local working tree or default branch represents the requested target. Avoid committing generated files. Do not create tags, merge PRs, deploy, or publish without explicit authorization.
