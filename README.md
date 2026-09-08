# AM2 Android Client

**Internal repository:** Not intended for public use or external contributions. Repository access and use require authorization by the repository owner.

AM2 Android push-to-talk client (`com.am2.tik`).

## Verification

```bash
python3 scripts/test_check_log_policy.py
python3 scripts/check_log_policy.py
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
```

Android builds, dependency resolution, emulators, compatibility matrices, signing, staging, and release publication use isolated developer environments or GitHub-hosted ephemeral CI—not the production VPS. Production release remains a separately approved signer, upgrade, artifact, and physical-device gate.

## Security

This repository does not provide a public vulnerability-reporting channel. Authorized personnel must use the security process assigned to their role. Do not include credentials, signing material, personal data, production data, or exploit details in tickets, logs, or pull requests.
