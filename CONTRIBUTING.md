# Contributing to event-store-adapter-kotlin

[日本語](CONTRIBUTING.ja.md)

## Overview

This guide is for developers who change this repository. For using the library, see [README.md](README.md).

The library is a thin Kotlin wrapper over the Java distribution. The build depends on it through `api("io.github.j5ik2o:event-store-adapter-java-core:5.2.0")` in `core/build.gradle.kts`. Java `5.2.0`, released on Maven Central, provides the SQLite, Spanner and Bigtable storages.

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
- Docker: the DynamoDB, Spanner and Bigtable tests need Docker. They use DynamoDB Local 3.3.1, the Cloud Spanner emulator 1.5.58 and the Cloud Bigtable emulator, each at a pinned digest (see the Conformance section of [README.md](README.md); the digests of the last two are in `SpannerEmulator` and `BigtableEmulator` under `src/test/java`, and CI pulls them before the tests). The SQLite tests use a real file in a temporary directory. A test that cannot start its emulator fails; it is never skipped.
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
python3 -m pip install -r .github/tests/requirements.txt
python3 -m unittest discover -s .github/tests -v
```

The `release-bundle` job builds the Maven Central bundle with `.github/central-bundle.sh` (the script the Release workflow uses) and a throwaway signing key, and checks its 180 files and 30 signatures. The `release-model` job checks the Quint model of the release path (`bash .github/quint/run-quint.sh ci`; see [.github/quint/README.md](.github/quint/README.md)). Neither uses a secret, and neither publishes anything.

The same job also verifies the SHA-256 values of `conformance/manifest.json`, `user-docs/template.json`, `tools/user-docs/check.py` and `api-surface/surface.json` with `sha256sum -c -`, and checks the workflow files with `actionlint` (`actionlint@v1.7.12`, installed by `go install`). The expected hash values are in `.github/workflows/ci.yml`.

Note that the `JavaCompile` tasks depend on `spotlessApply` in `build.gradle.kts`, so compiling can rewrite source formatting.

## Design history

This repository has no design documents of its own. The Kotlin design decisions are recorded in the hub's implementation plan:

- [Implementation plan](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md)
- [4.2 Main work for each language](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md#42-言語ごとの主な作業): Kotlin depends on the new Java API through `api(...)` and uses `await` from `kotlinx.coroutines`.
- [Decision of 2026-10-13](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/plan/implementation-plan.md#決めたこと2026-10-13): Kotlin and Scala expose the Java envelope types as they are, and the wrapper layer adapts only what differs by language (Kotlin nullable types and coroutines). Kotlin has no design document, so this record serves as the design-stage review.

## Changes and releases

- Changes go to `main` through a pull request. The `CI Success` job summarizes the results of lint, test, release-checks, release-bundle, release-model and aislop.
- The version is kept in the `version` file, which the build reads. Do not bump it by hand.
- The Bump-Version workflow is started manually, with the inputs `level` (`auto`, `patch`, `minor` or `major`), `force_bump`, `version` and `rehearsal`. Only `level=auto` derives the level from Conventional Commits (`.github/semver-level.py`): `!`, `BREAKING CHANGE:` or `BREAKING-CHANGE:` is major, `feat` and `revert` are minor, and the other types are patch. An explicit `level` is used directly. An explicit `version` bypasses the level and is used as the next version.
- Bump-Version does not push to `main`. It commits the new version to `version` on a branch `release/vX.Y.Z` and opens a pull request into `main`. It does not create a GitHub Release, and it does not set `version` back to a SNAPSHOT version.
- When a commit that changes `version` lands on `main`, the tag job of Bump-Version checks it with the release gate (`.github/release-gate.py`). The commit must be the squash merge of a pull request from `release/vX.Y.Z` of this repository, it must change only the version in `version`, and the version must be greater than the previous version and every `vX.Y.Z` tag. A commit whose parent has no `version` file is not released.
- Pushing a `vX.Y.Z` tag starts the Release workflow, which checks the tagged commit against the release gate again and publishes the version to Maven Central (see [Releasing](#releasing)). Snapshot builds are not published; use a released version.
- Dependency updates come from Renovate with the `chore(deps):` commit prefix.

The `core`, `dynamodb`, `spanner`, `bigtable` and `sqlite` projects provide the published modules. The root bundles their dependencies and runs the integration tests. Core uses compileOnly driver dependencies. Run `./gradlew :core:test :dynamodb:test verifyModulePoms` to verify isolated classpaths and generated POMs.

### Releasing

1. Run Bump-Version (`rehearsal: false`). Set `version` to choose the version, or leave it empty to use the bump level. The next version must be greater than the latest tag and than the version in `version` (`release-gate.py check-next`), so a version pull request never lowers it.
2. Check the pull request `version up to vX.Y.Z`. It must change only `version`, and Check unresolved comments must pass. Merge it with a squash merge when the release should go out. To cancel, close it and delete the branch.
3. The tag job (`Tag the merged version`) checks the merged commit against the release gate and pushes `vX.Y.Z`. The release gate also checks the pull request as a whole: from where it left `main` to its head, it must change only the version in `version`.
4. The Release workflow runs the job `Publish to Maven Central`:
   1. It checks the tagged commit against the release gate.
   2. It checks that Gradle builds the version of the tag.
   3. It checks Maven Central and the earlier release runs (`.github/central-portal.py precheck`). It waits while a deployment is in progress (up to 60 minutes, including the time of the queries and of `git ls-remote`; a server that sends the headers of one answer very slowly can stretch a query, and the job stops after 150 minutes). When the version is already published it skips the upload, and when an earlier attempt is unaccounted for it stops.
   4. It builds the bundle (180 files: six artifacts, each with the jar, sources, javadoc, POM and Gradle module file, their signatures and four checksums), checks the tagged commit against the release gate again (a newer tag may have been pushed while the bundle was built), and uploads the bundle to the Central Portal (`publishingType=AUTOMATIC`).
   5. It records the deployment ID as the artifact `central-deployment-<attempt>-<ID>` (kept for 90 days).
   6. It waits until the deployment is `PUBLISHED` (up to 60 minutes).
5. When the deployment is `PUBLISHED`, `Create the GitHub release` creates the GitHub release and `Check Maven Central` waits until repo1 serves the six POMs (up to 60 minutes). The release notes start from the previous `vX.Y.Z` tag that has a GitHub release, so the changes of a skipped version are in the next notes. The release is created without taking Latest; the next step, `Mark the greatest release as latest`, marks the greatest `vX.Y.Z` release it sees in the release list (drafts and pre-releases excluded) as Latest. This holds when the release list already shows the greatest version: if the list lags, a later job of an older version can mark that older version as Latest. Drafts and pre-releases also do not count as GitHub releases for the release notes.

A rehearsal (`rehearsal: true`) opens a draft pull request titled `version up to vX.Y.Z (rehearsal, do not merge)`. Close it without merging and delete the branch.

The signing secrets are given only to the bundle step, and the Sonatype secrets only to the precheck, upload and wait steps. `release.yml` has no personal access token.

Rules that keep the checks working:

- Do not create `vX.Y.Z` tags by hand, including tags created on the GitHub release page. A tag runs the Release workflow of the commit it points at. Do not tag commits from before the release gate was added to the Release workflow: their Release workflow publishes with no gate and no precheck.
- Do not delete Release runs or their `central-` artifacts, and keep the artifact retention at 90 days. The precheck reads them to find deployments of earlier attempts.
- Do not rename the jobs or steps of `release.yml`. The precheck finds the upload in earlier runs by the names in `.github/central-rules.py` (`RELEASE_JOB`, `UPLOAD_STEP`, `PRE_UPLOAD_STEPS`). If a name has to change, keep the old name in those sets as well.
- A pull request that changes `release.yml` changes `.github/tests/fixtures/release-workflow.yml` (the reviewed copy) in the same way. `.github/tests/test_release_workflow_shape.py` allows `PUSH_RELEASE_TAG: 'true'` only while `release.yml` is the same as that copy, line for line; only the versions in `uses:` lines may differ.
- A failed or cancelled run can be re-run for 30 days after it started.

To stop releases, merge a pull request that sets `PUSH_RELEASE_TAG` to `'false'` in `bump-version.yml`. While it is `'false'`, the tag job writes the tag it would push to the job summary and stops. A version pull request merged during that time leaves its version on `main` without a tag: set `version` back to the previous SNAPSHOT version with a pull request, then run Bump-Version again with `version` set to the version you want.

A re-run uses the workflow files of the commit it ran on, not those of `main`. While releases are stopped, do not re-run a tag job (`Tag the merged version`) that ran before the stop: its `bump-version.yml` still has `PUSH_RELEASE_TAG: 'true'`, so it pushes the tag. The same holds for a Release run of a tag pushed before the stop. Re-run them only after releases are started again.

### Until 5.2.0 is on Maven Central

The tag `v5.2.0` and its GitHub release exist, but 5.2.0 is not on Maven Central: the publish of 2026-11-11 was refused ([#908](https://github.com/j5ik2o/event-store-adapter-kotlin/issues/908)). 5.2.0 is published by re-running the failed jobs of that Release run ([42966589471](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/runs/42966589471)). The re-run uses the Release workflow of the tagged commit `7a49995`, which has no release gate. The precheck does not count that run (`FIRST_COUNTED_RUN_ID` in `.github/central-rules.py`).

- Until repo1 serves the six POMs of 5.2.0, the release gate refuses every newer version, in the tag job and in the Release workflow (`REQUIRED_PUBLISHED = '5.2.0'` in `.github/release-gate.py`). A version that is `PUBLISHED` in the Portal but not yet on repo1 is still refused.
- Do not merge a version pull request before 5.2.0 is on repo1. If one was merged, the tag job fails at the release gate: nothing is tagged and nothing is published. The merged version does not block the 5.2.0 re-run, because the re-run uses the workflow of `v5.2.0` and the release gate holds the merged version back. After repo1 serves the six POMs of 5.2.0, re-run the failed tag job.
- repo1 remembers a 404 for a while. Before you re-run a job that waits for 5.2.0, check the six POMs (`https://repo1.maven.org/maven2/io/github/j5ik2o/<artifact>/5.2.0/<artifact>-5.2.0.pom` for each artifact in `.github/central-bundle.py`).
- A failed tag job can be re-run for 30 days after it started. After that, the merged version is skipped: run Bump-Version with the next version (for example `version=5.2.2` when `5.2.1` is on `main` without a tag).
- Do not tag commits from before this release path. Their Release workflow publishes the version in `version` of the tagged commit, and those commits hold 5.2.0, a lower version or a SNAPSHOT version, so they cannot publish a version newer than 5.2.0. A tag there still publishes outside the release gate and the precheck, and a later precheck stops on that run because its job is named `Release`.
- When repo1 serves 5.2.0, open a pull request that sets `REQUIRED_PUBLISHED = None`. Until then, every gate run reads the six POMs of 5.2.0 from repo1.

### When a release stops

Most stops are recovered by re-running the failed jobs of the run (Re-run failed jobs). Each re-run checks Maven Central and the earlier attempts again before it uploads, so it never uploads a version twice.

Some stops need a line in the repository variable `CENTRAL_DEPLOYMENTS` (Settings, Secrets and variables, Actions, Variables). Write one line per item:

- `<run_id>/<attempt>=<deployment ID>`: the deployment that attempt `<attempt>` of run `<run_id>` uploaded.
- `<run_id>/<attempt>=none`: that attempt uploaded nothing, or its deployment was dropped in the Portal.
- `tag/vX.Y.Z=checked`: you checked in the Portal that no deployment of that version is in progress.

When the precheck stops for a missing ID, its error message lists every line to add, for every attempt from `<run_id>/1` to `<run_id>/<run_attempt>` that lacks one. The deployment of an attempt is named `event-store-adapter-kotlin-<version>-run<run_id>-<attempt>` in the Portal.

| What happened | What the workflow does | How to recover |
|---|---|---|
| 5.2.0 is not on repo1 yet (`must wait until 5.2.0 is published on Maven Central`) | The release gate fails in the tag job or the Release workflow. Nothing is tagged, built or uploaded | Publish 5.2.0 first (see [Until 5.2.0 is on Maven Central](#until-520-is-on-maven-central)). When repo1 serves the six POMs, re-run the failed job within 30 days |
| repo1 could not be read for 5.2.0 (`could not read whether 5.2.0 is on Maven Central`) | The release gate fails | Re-run the failed job |
| The tag is not on the squash merge of a version pull request, the commit changes code with the version, the version is lower, or the tag does not match the version | The release gate step fails. Nothing is built or uploaded | Delete the tag. Fix `version` with a pull request |
| Gradle builds another version than the tag | `Check the version Gradle builds` fails | Fix `version` with a pull request and release the next version |
| A query to GitHub, the Portal or repo1 fails (5xx, timeout, a body cut in the middle, or a body that does not finish before the deadline), including the release gate re-reading each pull request of the commit | The precheck, or the release gate step, stops | Re-run the failed jobs |
| A deployment of another run is in progress | The precheck waits up to 60 minutes, then fails | Re-run the failed jobs |
| An attempt may have uploaded but left no deployment ID (including step lists that are cut, runs without jobs, and attempts without a release job) | The precheck stops and lists the lines to add | Find each ID in the job summary or log of the attempt, or in the Portal by its name. Add `<run_id>/<attempt>=<ID>` or `=none` for every listed attempt, then re-run |
| The upload does not return 201 and a deployment ID | The upload step fails. The next attempt stops as in the row above | As in the row above |
| The bundle does not have the expected files | The bundle step fails. Nothing is uploaded | Fix it with a pull request and release the next version. A re-run uses the scripts of the tagged commit, so it does not pick up the fix |
| The signing key is missing or wrong | Signing fails in the bundle step. Nothing is uploaded | Fix the secrets and re-run |
| The deployment is `FAILED` | The wait step fails with the Portal errors. No GitHub release is created | Re-run the failed jobs (the precheck sees `FAILED` and uploads again). If the content is wrong, fix it in the next version |
| A `FAILED` deployment was dropped in the Portal | The precheck stops: the ID is not found | After the drop, add `<run_id>/<attempt>=none` and re-run |
| The deployment is still in progress after 60 minutes | The wait step fails | Re-run the failed jobs. The precheck waits for the same deployment and does not upload again |
| repo1 does not serve the version after 60 minutes | `Check Maven Central` fails | Re-run only `Check Maven Central` |
| Creating the GitHub release fails, or the list of GitHub releases cannot be read | `Create the GitHub release` fails. It does not read a failed listing as "no release" | Re-run only `Create the GitHub release` |
| The Latest release is not the greatest version (the release list did not show the greatest version yet when a job of an older version read it, or a newer release was created between the listing and the edit) | | After the release list shows the greatest version, mark it as Latest again: re-run `Create the GitHub release` of the greatest version, or run `gh release edit v<greatest> --latest` |
| The GitHub release of the version exists only as a draft or a pre-release | `Create the GitHub release` fails (`is a draft or a pre-release`) | Publish the draft as a normal release, or delete it, then re-run only `Create the GitHub release` |
| A newer tag exists (the version was overtaken) | The release gate stops (`must be greater than the latest tag`); the version is not published | Leave it: the version is skipped. If the version was already `PUBLISHED` (its wait timed out, and the newer tag came before the re-run), see [A version published before a newer tag](#a-version-published-before-a-newer-tag) |
| The waiting publish job was replaced by a later arrival in the `release-publish` group (another tag push, or a re-run of another version) | That run is cancelled before it starts; it publishes nothing | If its version is the latest `vX.Y.Z` tag, re-run all jobs of the cancelled run (its cancelled job is not counted as an upload). Otherwise a re-run stops at the release gate because a newer tag exists: if an earlier attempt already published the version (for example, only the GitHub release had failed), see [A version published before a newer tag](#a-version-published-before-a-newer-tag); if not, leave it (the version is skipped) |
| The tag job (`Tag the merged version`) was replaced by a later arrival in the `release-tag` group | That run is cancelled; the version gets no tag | If its version is greater than the latest `vX.Y.Z` tag, re-run the cancelled run, also when other pull requests were merged after it (the release gate does not require the head of `main`). Otherwise leave it: the release gate refuses it |
| A line of `CENTRAL_DEPLOYMENTS` is malformed, gives another ID than the artifact, or says `none` for a deployment the Portal still knows | The precheck stops | Correct or remove the line and re-run |
| A Release run was deleted, or a tag push started no Release run (more than three tags in one push, or an outage) | The precheck stops: a tag has no Release run (`tag vX.Y.Z has no Release run`) | Check in the Portal that no deployment of that version is in progress, add `tag/vX.Y.Z=checked` and re-run. Do not move the tag |
| The Release run is still there, but the `central-deployment-<attempt>-<ID>` artifact of an attempt was deleted | The precheck stops: that attempt may have uploaded but has no deployment ID, and it lists the lines to add. `tag/vX.Y.Z=checked` does not help here: it only covers a tag without a run | Find the ID in the job summary or log of that attempt, or in the Portal by the name `event-store-adapter-kotlin-<version>-run<run_id>-<attempt>`. Add `<run_id>/<attempt>=<ID>`, or `<run_id>/<attempt>=none` if the Portal has no such deployment, then re-run |
| A job or step of `release.yml` was renamed | The precheck stops on the earlier runs | Do not rename them. If you have to, keep the old names in `.github/central-rules.py` |
| The artifact retention was set below 90 days, or a record expired | The precheck stops (`expires before 90 days` or `has expired`) | Set the retention back to 90 days for new records. Records that already exist keep their early expiry date: add the line the error message gives, `<run_id>/<attempt>=<ID>` with the ID of that record, and re-run. The precheck then reads the state of that deployment in the Portal as usual. If the Portal dropped that deployment, add `<run_id>/<attempt>=none` instead: the precheck still queries the ID of the record and goes on only when the Portal does not know it |
| No `PUBLISHED` deployment of the version is known (its deployment ID is not visible), and repo1 serves only some of the six POMs (`repo1 serves only N of the 6 POMs`) | The precheck stops. Nothing is uploaded and no GitHub release is created. When a known deployment of the version is `PUBLISHED`, the precheck does not stop here: the version is published, and `Check Maven Central` waits for repo1 | A published deployment may still be spreading: re-run the job in a few minutes. If it stays, check the deployment in the Portal |
| The version pull request as a whole changes more than the version (for example, an earlier commit of the pull request changes code), or its head could not be read (`as a whole must change only the version`) | The release gate fails in the tag job or the Release workflow. Nothing is tagged or uploaded | If the head could not be read (`could not read the head` in the log, for example right after a force push), re-run the failed job. Otherwise restore the version with a pull request |
| A newer tag was pushed while the bundle was built | `Check the release gate again before the upload` fails (`must be greater than the latest tag`). Nothing is uploaded | Leave it: the version is skipped |
| Bump-Version stops at `Get the next version` (`must be greater than the version ... in version`) | A version pull request was merged but not tagged, so the next version would not be above `version`. No pull request is opened | Re-run the failed tag job if it can still release the merged version, or run Bump-Version with the `version` input set above the version in the `version` file |
| More than 30 days passed, so the run cannot be re-run | | Treat the version as skipped and release the next one. Before that, add lines for every attempt that is still unaccounted for |
| A run is older than 80 days | The precheck does not count it | |

#### A version published before a newer tag

When a version is on Maven Central but a newer `vX.Y.Z` tag was pushed before its run finished, the release gate stops every re-run of the older version. This happens when the wait for its deployment timed out and the Portal published it later, or when an earlier attempt published it, a later step failed (for example, creating the GitHub release), and the re-run was replaced in the `release-publish` group. The GitHub release and the repo1 check are then missing. Recover them by hand:

1. Check that repo1 serves the version: `https://repo1.maven.org/maven2/io/github/j5ik2o/<artifact>/<version>/<artifact>-<version>.pom` for each of the six artifacts in `.github/central-bundle.py` (`ARTIFACTS`).
2. Write the release description from the previous release tag `<previous>`:

   ```sh
   python3 .github/create-release-note-header.py https://github.com j5ik2o/event-store-adapter-kotlin <previous> vX.Y.Z > release-body.md
   git log <previous>..vX.Y.Z --no-merges --format='%H%x1f%s%x1f%b%x1e' \
     | python3 .github/create-release-note-body.py https://github.com j5ik2o/event-store-adapter-kotlin >> release-body.md
   ```

3. Create the release without taking Latest: `gh release create vX.Y.Z --verify-tag --title "Release vX.Y.Z" -F release-body.md --latest=false`.
4. Mark the greatest `vX.Y.Z` release as Latest: `gh release edit v<greatest> --latest`.

The release gate is not relaxed for this case: it keeps checking, without any secret, that a version is greater than every other tag before the precheck runs.
