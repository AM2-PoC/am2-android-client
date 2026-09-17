# Contributing

Repository access and contributions require authorization. Do not change visibility, grant access, weaken repository controls, or distribute source or release artifacts outside approved channels.

## Workflow

1. Branch from current `main` using `feat/`, `fix/`, `docs/`, `test/`, `refactor/`, `ci/`, or `chore/`.
2. Keep each pull request focused on one change.
3. Use Conventional Commits.
4. Add the smallest regression test that proves changed behavior.
5. Update documentation when a contract or release procedure changes.
6. Merge only after review and required checks pass.

Do not push directly to `main`. Production publication requires signer continuity, install-over verification against the active release, affected physical-device acceptance, explicit approval, and rollback evidence.

## Local checks

```bash
python3 scripts/check_log_policy.py
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
git diff --check
```

Use the affected CI lane for compatibility or release-sensitive changes. Documentation-only changes do not require the full emulator matrix.

## Repository hygiene

Do not commit APK/AAB files, keystores, credentials, personal or production data, `local.properties`, generated build output, local IDE state, or assistant workspaces. Keep comments focused on current contracts and non-obvious compatibility constraints.

Report vulnerabilities through the team's security channel; do not place undisclosed details in issues or pull requests.
