# Contributing

## Workflow

1. Create a short-lived `feat/`, `fix/`, `docs/`, `test/`, `refactor/`, `ci/`, or `chore/` branch from current `main`.
2. Keep one coherent change per pull request and use Conventional Commits.
3. Add or update the smallest test that proves the behavior.
4. Open a pull request and wait for every required check and review.
5. Never commit APK/AAB files, keystores, credentials, `local.properties`, generated build output, or personal IDE/device state.

Android builds, dependency resolution, emulators, and compatibility matrices run only on an isolated developer machine or GitHub-hosted ephemeral runner—never on the production VPS.

## Verification

```bash
python3 scripts/check_log_policy.py
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
git diff --check
```

Do not run the full compatibility matrix for documentation-only changes. Release, staging, signing, publication, emulator, and production actions remain separate approved gates.

## Security reports

Follow [`SECURITY.md`](SECURITY.md). Never include secrets, personal data, signing material, or production credentials in issues, pull requests, logs, or fixtures.
