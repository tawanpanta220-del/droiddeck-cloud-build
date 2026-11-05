# DroidDeck Cloud Build

Build the upstream DroidDeck baseline APK using GitHub-hosted Ubuntu runners.

- [Latest workflow runs](https://github.com/tawanpanta220-del/droiddeck-cloud-build/actions/workflows/cloud-baseline.yml)
- [Initial build](https://github.com/tawanpanta220-del/droiddeck-cloud-build/actions/runs/41967966344)
- [Pinned upstream source](https://github.com/Droid-Deck/DroidDeck/tree/55362d68efbf5ba6ff8f302b914a1d37d0b73a06)

The baseline uses upstream commit `55362d68efbf5ba6ff8f302b914a1d37d0b73a06`.
The repository contains the build workflow; each job checks out that upstream source.

This is the first build milestone for the Google Chrome Desktop Edition project.
The current output is the original DroidDeck baseline, with its original launcher.
Chrome installation, launch, sandbox and Android device compatibility remain untested.

## Results

After a successful run, download the artifacts from its Actions page:

- `droiddeck-baseline-apk`: APK signed with the upstream development test key.
- `droiddeck-baseline-packs`: runtime add-on packs.
- `droiddeck-baseline-evidence`: checksum, source identifiers and available build/signature logs.
- `unit-test-results`: Android unit-test reports.

A change to `.github/workflows/cloud-baseline.yml` starts a build automatically.
You can also select **Run workflow** on the workflow page.

## Source attribution

The workflow is adapted from the upstream DroidDeck CI at the pinned revision.
The upstream GPLv3 license text is included in `LICENSE`.
