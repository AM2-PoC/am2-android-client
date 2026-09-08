# Internal contribution workflow

This repository is not intended for public contributions. Access and contribution require authorization by the repository owner.

## Workflow

1. Work only in the access scope approved for your role.
2. Create a short-lived `feat/`, `fix/`, `docs/`, `test/`, `refactor/`, `ci/`, or `chore/` branch from current `main`.
3. Keep one coherent change per pull request and use Conventional Commits.
4. Add or update the smallest test that proves the behavior.
5. Obtain the required review and all required checks before merge.
6. Do not push directly to `main`, grant access, alter repository visibility, weaken security controls, or share source/artifacts outside approved channels.
7. Never commit APK/AAB files, keystores, credentials, personal data, customer data, `local.properties`, generated build output, or local editor/AI state.

Android builds, dependency resolution, emulators, compatibility matrices, signing, staging, and release publication run only on isolated developer systems or GitHub-hosted ephemeral CI—not the production VPS.

## Verification

```bash
python3 scripts/check_log_policy.py
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
git diff --check
```

Do not run a full compatibility matrix for documentation-only changes. Release, staging, signing, publication, and production actions remain separate approved gates.

## Security reports

Use the approved internal security-reporting channel. Do not place undisclosed vulnerability details, credentials, signing material, personal data, customer data, or destructive proof-of-concept payloads in issues, pull requests, logs, or fixtures.
