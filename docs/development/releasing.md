# Releasing Muse

Muse follows a lightweight GitFlow model. The normal development branch carries the next Maven `-SNAPSHOT`; `main` carries a non-SNAPSHOT version, and only an authorized release tag triggers the Maven Central publication workflow.

## Version policy

- Keep all Muse reactor coordinates aligned, including `muse-bom/pom.xml`, its `muse.version` property, the root parent reference, the HTTP reactor, connector and adapter modules, Maven plugin modules, test fixtures, and example POMs that depend on the current Muse build.
- As of the initial branch split, `main` remains on `0.1.0` and `develop` uses `0.2.0-SNAPSHOT`. These are source version values, not evidence that the corresponding artifacts have been published.
- A release branch uses the exact target version without the `-SNAPSHOT` suffix. Once that release is complete, update `develop` to the following development version, e.g. `0.3.0-SNAPSHOT` after `0.2.0`.
- Treat SNAPSHOT artifacts as development artifacts. The tag-based Maven Central workflow is for stable releases; snapshot publishing requires a separate explicitly configured destination and must not happen implicitly.

## Release procedure

1. Verify `develop` is ready: review its intended changes, CI status, dependency and module versions, and pending pull requests.
2. Create `release/<version>` from the selected `develop` commit. Change all relevant Maven coordinates and internal references from the development snapshot to the exact stable version.
3. Validate the effective Maven versions and run the required CI/build and integration/acceptance suites appropriate to the release. The standard full reactor command is `mvn clean verify --batch-mode -Dkotlin.daemon.enabled=false`; use the provider, Invoker, packaged E2E, and architecture verification guidance in [Contributor verification](verification.md) for additional release confidence.
4. Review the release branch and open a PR to `main`. Do not merge until release verification and approvals are complete.
5. After the merge, confirm `main` HEAD contains the exact stable POM versions. Create and push an annotated tag named `v<version>` that points to that same HEAD. Never tag a detached or older commit; the current publication workflow requires the tag commit to equal the current `main` HEAD.
6. Monitor [Publish to Maven Central](../../.github/workflows/publish.yml) and check artifact validation. The Central publishing extension sets `autoPublish=false`, so final publication in Central Portal needs a separate manual approval.
7. Reconcile any release-only fixes into `develop` without overwriting its SNAPSHOT version, and advance the snapshot to the next intended release. Keep `main` on the released non-SNAPSHOT version.

## Hotfix procedure

Create `hotfix/*` from the released `main` commit, apply a minimal fix, update the version to the selected patch release, and verify it. Use a PR to `main` followed by a release tag on its new HEAD; carry the code fix back to `develop` while preserving its independent snapshot version.

## CI and version invariants

- [Maven CI](../../.github/workflows/maven.yml) currently listens to pushes and pull requests for `main` and `develop`; enforce successful required checks in GitHub branch rules separately.
- [Publish to Maven Central](../../.github/workflows/publish.yml) currently triggers on pushed `v*` tags and requires a strict `major.minor.patch` version, a matching Maven `project.version`, and the tag at the current `main` HEAD.
- `AGENTS.md` requires stable release versions, matching tag/POM versions, source/Javadoc/signature artifacts for published JARs, and `skipPublishing=true` for non-publishable fixtures and test modules.
- A successful PR build does not by itself prove release publication; distinguish tested, tagged, uploaded, validated, and manually published states.
