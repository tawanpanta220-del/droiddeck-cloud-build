# DroidDeck Cloud Build

Build the upstream DroidDeck baseline APK using GitHub-hosted Ubuntu runners.

- [Successful baseline run #1](https://github.com/tawanpanta220-del/droiddeck-cloud-build/actions/runs/41967966344)
- [Latest workflow runs](https://github.com/tawanpanta220-del/droiddeck-cloud-build/actions/workflows/cloud-baseline.yml)
- [Pinned upstream source](https://github.com/Droid-Deck/DroidDeck/tree/55362d68efbf5ba6ff8f302b914a1d37d0b73a06)

The baseline uses upstream commit `55362d68efbf5ba6ff8f302b914a1d37d0b73a06`.
This repository carries the build workflow; every job checks out that upstream source.

This is the baseline build milestone for the Google Chrome Desktop Edition project.
The APK retains the original DroidDeck launcher. Chrome installation, launch,
sandbox and Android device compatibility remain untested.

## Verified results

Run #1 completed successfully on 2026-11-05. All seven jobs succeeded.

- ARM64 DroidDeck 0.6.9 APK (version code 17), 91,056,198 bytes.
- Android unit tests: 3,067 tests, 435 suites, no failures, errors or skipped cases.
- Python tests: 1,616 cases including 49 skipped; no failures or errors.
- Native regression checks succeeded. Rust host tests: 171 passed.
- APK development test-key signatures verified for v1, v2 and v3.
- Downloaded APK SHA-256 matches the checksum recorded by the build runner:
  `5d6fe0f034d09f9b2d2ec0aace64070ec38728cfc5cacd4ec016b4554bb8337d`.

The APK manifest declares arm64-v8a, minSdk 26 and targetSdk 28.
Those declarations do not establish device compatibility. No Android device
installation or graphical runtime tests have been performed.

## Download artifacts

Open the completed run's Actions page:

- `droiddeck-baseline-apk`: APK signed with the upstream development test key.
- `droiddeck-baseline-packs`: corresponding runtime add-on packs.
- `droiddeck-baseline-evidence`: checksum, source identifiers and build/signature logs.
- `unit-test-results`: Android JUnit XML and HTML reports.

APK and packs have 30-day retention; evidence and test reports have 14-day retention.
This CI baseline downloads add-on packs at runtime rather than bundling them.
The required PRoot and DirectAudio URLs responded successfully in the post-build
check. The optional GOG Comet companion URL returned 404; the matching built pack
is available in the packs artifact. Runtime downloads still require device tests.

A change to `.github/workflows/cloud-baseline.yml` starts a build automatically.
You can also select **Run workflow** on the workflow page.

## Chrome integration gate

Before changing the launcher or adding the Chrome first-run installer, the project
requires an actual Chrome window and sandbox evidence on an Android ARM64 device.
The baseline retains upstream paths that can inject sandbox-disabling flags.
A release must not default to `--no-sandbox`. Chrome release approval remains blocked
pending those device checks.

## Source attribution

The workflow is adapted from the upstream DroidDeck CI at the pinned revision.
The upstream GPLv3 license text is included in `LICENSE`.
