# Release path model (Quint)

`release_path.qnt` models the release path: the version pull request, the merge, the release gate, the tag, the publish job, the Maven Central deployment (accepted, then `PUBLISHED` or `FAILED`), the checks after publishing and the GitHub release. `release_path_test.qnt` runs fixed paths through the model.

The model was written for the release path design of the event-store-adapter repositories (the js shape of j5ik2o/event-store-adapter-js#1158 applied to java, kotlin, scala, rs and go). The R2b part models what `.github/central-rules.py` decides before an upload:

- `RECORD_VIEW`: the precheck sees a deployment only through its record (the `central-deployment-<attempt>-<ID>` artifact, or a line in the `CENTRAL_DEPLOYMENTS` variable). A job can fail after the upload step starts and before the ID is recorded.
- `UNKNOWN_DETECTED`: such an attempt is always seen as started (`may_have_uploaded` in `central-rules.py` counts a job as uploaded unless it proves otherwise).
- `UNKNOWN_STOPS`: an attempt that may have uploaded without a record stops every later upload.
- `NONE_SKIPS_QUERY`: the rejected rule where a `=none` line skipped the status query. The instance `noneSkipsQuery` shows that rule breaks the invariants.
- `PRECHECK_LATEST_TAG`: the precheck checks that this version is still the latest tag. The instance `noLatestTagCheck` drops the check and breaks I2 (a `FAILED` version re-run after a later version is `PUBLISHED`), which shows the check is needed. The model decides and uploads in one action, so it always reads the tags at the moment it decides; that `central-portal.py` reads the tags again after a wait is checked by `test_precheck_rereads_the_tags_after_waiting`.

- Concurrency groups (`release-tag` for the tag job, `release-publish` for the publish job): a job that arrives in a group replaces the job waiting there. Every arrival counts, the first run and every re-run (of a failed job or of a cancelled run), whatever its version or commit, so a re-run of an older version can replace the waiting job of a newer one ([#863](https://github.com/j5ik2o/event-store-adapter-java/issues/863)). `RERUN_CANCELLED`: a person re-runs a cancelled run. The instance `cancelledLeftAlone` leaves cancelled runs alone (the recovery row before #863) and breaks I8 and I8b: the latest tag stays unpublished, or a merged version above the latest tag stays untagged, with nothing left to run. The tag gate does not require the commit to be the head of `main` (a merge that does not change the version can come after it), so I8b checks every untagged version commit above the latest tag.

This repository is event-store-adapter-kotlin. The model is the one of j5ik2o/event-store-adapter-java (`.github/quint/` at `3439740`), with one addition (`portalResolveAt`, a deployment that becomes `PUBLISHED` with a fixed delay before repo1 serves it). The kotlin instances have the R4 shape of this repository:

- `LEGACY = Set(1)`: the tag `v5.2.0` exists, but 5.2.0 is not on Maven Central. It is published by re-running the 2026-11-11 Release run, which has no release gate (`publishLegacy`).
- `REQUIRED_PUBLISHED = Set(1)`: the release gate setting `REQUIRED_PUBLISHED = '5.2.0'` in `.github/release-gate.py`. The gate refuses every newer version, in the tag job and in the Release workflow, until repo1 serves 5.2.0 (`requiredPublished` reads `isVisible`, like `central-portal.py published` reads the six POMs).
- `RECORD_VIEW = true`: the precheck sees deployments only through their records, as in `javaR2b`.

`kotlin`, `kotlinNoManual` and `kotlinReleasesOnly` are expected to have no violation; `kotlinNoRequired` drops the setting and breaks I6b (`legacyBeforeNewer`: a newer version is published before 5.2.0). `kotlinPathsTest` runs the fixed paths: newer versions wait for 5.2.0 (`legacyFirstTest`), a `FAILED` 5.2.0 re-run (`legacyFailedTest`), 5.2.0 `PUBLISHED` but not yet on repo1 (`legacyVisibleLaterTest`), and two releases after 5.2.0 (`legacyThenTwoReleasesTest`).

Invariants are `safety` (I1 to I11, with I8b `untaggedReleaseRecoverable`, I6c `requiredVisibleBeforeNewer` and I8c `publishedReleaseRecoverable`). Instances whose names describe a missing safeguard (`stepRecordDropped`, `unknownIgnored`, `noneSkipsQuery`, `cancelledLeftAlone` and others) are expected to violate an invariant.

The model files are those of j5ik2o/event-store-adapter-scala at `48e4256` (review on scala#934), taken over in #925. They change the model of `780eb20` as follows, all checked against `release.yml` of this repository; the kotlin instances keep their constants:

- `jobOk`: the release job ended with success, because its wait step read `PUBLISHED` (`confirmPoll`) or its precheck read the deployment as published (`resumePublishJob`, a run or re-run that uploads nothing). `githubRelease` needs it, as the `github-release` job needs the outputs of the release job. A job that fails after the upload, or whose wait times out while the deployment is still processing, leaves no confirm and no release: the recovery is to re-run the release job. `rerunConfirm` is only for the repo1 timeout of the `Check Maven Central` job. `supplyId` no longer starts the confirm by itself.
- I6c `requiredVisibleBeforeNewer`: a version newer than 5.2.0 is in flight or published only when 5.2.0 is visible on repo1, not only `PUBLISHED` in the Portal. I6b alone does not catch a gate that reads the Portal state (changing `requiredPublished` to read `published` violates I6c in the kotlin instances, not I6b).
- I8c `publishedReleaseRecoverable`: a published latest version without a GitHub release can still get one by the documented steps.
- `noLatestTagCheck` drops the latest-tag check of the model's single publish action, which stands for both the publish gate and the precheck (the model decides and uploads in one step). It shows that a latest-tag check right before the upload is needed; `release.yml` makes it three times (the gate, the precheck after its wait, and the gate again right before the upload). A short window stays after the last check, but the publish jobs run one at a time in the `release-publish` group, so a newer version is not published first.

Outside the model, checked by the tests in `.github/tests/`: the deadline of the precheck, the wait and the check of repo1 (a timeout that can come before the attempt limit; its recovery is the re-run of `publishFailed`), the whole diff of the version pull request, the release-note base and the Latest release, the artifact records with an early expiry, and repo1 serving only some POMs.

## Running

```sh
bash .github/quint/run-quint.sh ci          # what CI runs: typecheck, every quint test, the kotlin and R2b instances with 500 samples
bash .github/quint/run-quint.sh full 3000   # every instance; run it locally when the model changes and put the result in the pull request
```

The script uses `quint` from `PATH`, or the command in `QUINT` (CI uses `npx --yes @informalsystems/quint@0.32.0`).

quint fetches its Rust evaluator (`evaluator/v0.6.0`) from the GitHub API without authentication when `$QUINT_HOME/rust-evaluator-v0.6.0/quint_evaluator` is missing (`QUINT_HOME` defaults to `~/.quint`). That API allows 60 requests an hour per IP, which shared runners often have used up. CI therefore runs `fetch-evaluator.sh` first: it downloads the evaluator with `gh` and the job's token, checks its SHA-256, and puts it in `$QUINT_HOME` (taken from j5ik2o/event-store-adapter-rs#375).

When `central-rules.py` changes what it decides, change the model first, then the code.
