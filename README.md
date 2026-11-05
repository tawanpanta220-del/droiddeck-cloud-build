# DroidDeck Cloud Build

Build the pinned upstream DroidDeck baseline using GitHub-hosted Ubuntu runners.

- [Successful baseline run #1](https://github.com/tawanpanta220-del/droiddeck-cloud-build/actions/runs/41967966344)
- [Workflow runs](https://github.com/tawanpanta220-del/droiddeck-cloud-build/actions/workflows/cloud-baseline.yml)
- [Pinned upstream source](https://github.com/Droid-Deck/DroidDeck/tree/55362d68efbf5ba6ff8f302b914a1d37d0b73a06)
- [Mate 20 Pro device test](docs/chrome/MATE20PRO_TEST.md)

This repository carries the build workflow. Every source job checks out upstream
commit `55362d68efbf5ba6ff8f302b914a1d37d0b73a06`. The baseline retains the original
DroidDeck launcher. No Chrome Edition APK or product installer has been completed.

## Verified build

Run #1 completed successfully on 2026-11-05; all seven jobs succeeded.

- ARM64 DroidDeck 0.6.9 APK, version code 17, 91,056,198 bytes.
- Android unit tests: 3,067 tests, 435 suites, no failures/errors/skips.
- Python: 1,616 cases including 49 skipped; no failures/errors.
- Native regression checks succeeded; Rust host tests: 171 passed.
- Development test-key APK signatures verified for v1, v2 and v3.
- APK SHA-256:
  `5d6fe0f034d09f9b2d2ec0aace64070ec38728cfc5cacd4ec016b4554bb8337d`.

The APK was subsequently installed on a HUAWEI Mate 20 Pro. Its installed APK
checksum matches the downloaded and cloud-built APK.

## Device and Chrome gate

The actual device reports Android 10/API 29, EMUI 12.0.0 and kernel 4.14.116.
The Android frontend and ubuntu-r1-rc4 runtime install successfully.
The upstream GPU classification remains unsupported (Mali-G76).

A fast-path statx defect was isolated with a stat/ls comparison. Disabling that
performance path fixes those file operations. The Desktop guest reaches READY
after compatibility adjustments, but its picture remains black on both ADB and
the physical display. A first-frame event is not a visual-success criterion.

Google Chrome Stable ARM64 157.0.8103.38-1 was authenticated through Google's
GPG-signed repository metadata and the package SHA-256. After diagnostic
extraction into an app-private prefix, `--version` succeeds. A headless browser
startup with sandbox aborts at the SUID sandbox ownership check; the helper is owned by
Android UID rather than root. A user-namespace probe returns EINVAL.

**Chrome Edition is BLOCKED on this tested device/runtime.** No `--no-sandbox`
flag was used, no sandbox fallback was added, and no graphical Chrome/HTTPS
success is claimed. The launcher/installer milestone waits for the required
runtime and sandbox proof.

Temporary debugging and stay-awake settings were restored, preloads restored,
and the Linux session stopped. See the linked device report for the exact scope.

## Artifacts

Download from the completed run's Actions page:

- `droiddeck-baseline-apk`: test-key APK.
- `droiddeck-baseline-packs`: matching runtime add-on packs.
- `droiddeck-baseline-evidence`: checksum, source and build/signature evidence.
- `unit-test-results`: Android JUnit XML/HTML reports.

APK and packs have 30-day retention; test/evidence artifacts have 14-day retention.
This baseline downloads add-on packs at runtime. Required PRoot/DirectAudio
downloads were exercised during the device runtime installation. The optional
GOG Comet pack URL returned 404 in the earlier URL check; its matching built pack
is retained in the packs artifact.

A workflow-file change triggers a build. **Run workflow** also starts a build.

## Attribution

The workflow is adapted from upstream DroidDeck CI at the pinned revision.
The upstream GPLv3 license text is included in `LICENSE`.
