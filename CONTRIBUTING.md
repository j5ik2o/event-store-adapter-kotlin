# Contributing to event-store-adapter-kotlin

[日本語](CONTRIBUTING.ja.md)

## Overview

This guide is for developers who change this repository. For using the library, see [README.md](README.md).

The library is a thin Kotlin wrapper over the public Java distribution. The build depends on it through `api("io.github.j5ik2o:event-store-adapter-java:5.0.1")` in `build.gradle.kts`.

## Shared contract and decisions

This library follows common contract v5. The shared specification and the design decisions (ADRs) are kept in the hub repository:

- Specification: <https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec>
- Design decisions: <https://github.com/j5ik2o/event-store-adapter/tree/main/docs/adr>

This repository holds two copies derived from the hub. Both are read-only inputs for this guide:

- [conformance data](conformance/README.md) under `conformance/`
- [API surface check](api-surface/README.md) under `api-surface/`

## Local setup

- Gradle: use the Gradle Wrapper, `./gradlew`. It is pinned to Gradle 9.8.2 in `gradle/wrapper/gradle-wrapper.properties`.
- JDK: CI installs JDK 11, the JDK of the test matrix, and JDK 25 for the test job, and the last JDK (25) runs Gradle. The build compiles for Java 11 (`jvmToolchain(11)` and `options.release.set(11)` in `build.gradle.kts`). The JVM that runs the tests is chosen with `-PtestJavaVersion` and defaults to 11. CI runs the test matrix with JDK 11, 17 and 19.
- Docker: the DynamoDB tests need Docker. They use DynamoDB Local 3.3.1 at a pinned digest (see the Conformance section of [README.md](README.md)).
- Python 3: it runs the documentation and conformance-data checks. CI uses Python 3.15. `tools/user-docs/check.py` requires Python 3.10 or later.

## Checks

These are the commands that CI runs (`.github/workflows/ci.yml`) or that the README documents.

Lint:

```
./gradlew spotlessCheck
```

Tests, with the JVM version for the tests (CI uses 11, 17 and 19). The conformance report is written to `build/reports/conformance/report.json`:

```
./gradlew test -PtestJavaVersion=11
```

Build and lint together (README.md, Conformance section):

```
./gradlew build spotlessCheck
```

API surface check only (see [api-surface/README.md](api-surface/README.md)):

```
./gradlew test --tests '*apisurface*'
```

Release automation checks (the `release-checks` job of CI):

```
python3 tools/conformance/manifest.py verify
python3 tools/user-docs/check.py
python3 -m unittest discover -s .github/tests -v
```

The same job also verifies the SHA-256 values of `conformance/manifest.json`, `user-docs/template.json`, `tools/user-docs/check.py` and `api-surface/surface.json` with `sha256sum -c -`, and checks the workflow files with `actionlint` (`actionlint@v1.7.12`, installed by `go install`). The expected hash values are in `.github/workflows/ci.yml`.

Note that the `JavaCompile` tasks depend on `spotlessApply` in `build.gradle.kts`, so compiling can rewrite source formatting.

## Design history

This repository has no design documents of its own. The Kotlin design decisions are recorded in the hub's implementation plan:

- [Implementation plan](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md)
- [4.2 Main work for each language](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md#42-言語ごとの主な作業): Kotlin depends on the new Java API through `api(...)` and uses `await` from `kotlinx.coroutines`.
- [Decision of 2026-10-13](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md#決めたこと2026-10-13): Kotlin and Scala expose the Java envelope types as they are, and the wrapper layer adapts only what differs by language (Kotlin nullable types and coroutines). Kotlin has no design document, so this record serves as the design-stage review.

## Changes and releases

- Open pull requests against `main`. CI has an aggregate job, `CI Success`, which waits for the lint, test and release-checks jobs.
- The `version` file holds the version (currently `5.0.2-SNAPSHOT`). Do not bump it by hand.
- Versions are bumped by the manual `Bump-Version` workflow (`workflow_dispatch`). Its inputs are `level` (`auto`, `patch`, `minor` or `major`), `force_bump` and `version`. Only `level=auto` derives the level from Conventional Commits. An explicit `level` is used directly. An explicit `version` bypasses level calculation and is used as the next version.
- Pushing a tag `vX.Y.Z` runs the `Release` workflow, which publishes to Sonatype. After successful CI on `main`, `Snapshot` publishes only when the run is not from a pull request, the snapshot commit matches the successful CI commit, and that commit is still the tip of `main` immediately before publishing.
- Dependency updates come from Renovate with the `chore(deps):` commit prefix.
