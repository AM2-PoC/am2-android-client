# AM2 Android Client

Android push-to-talk client (`com.am2.tik`).

## Requirements

- JDK 21
- Android SDK 35
- Build Tools 35.0.0
- NDK 28.2.13676358
- CMake 3.22.1

## Verify

```bash
python3 scripts/test_check_log_policy.py
python3 scripts/check_log_policy.py
./gradlew --no-daemon :app:testDevDebugUnitTest :app:assembleDevDebug
```

Compatibility matrices and signed artifact generation run through approved GitHub Actions lanes. Production publication additionally requires signer continuity, install-over verification against the active release, affected physical-device/network acceptance, explicit approval, and rollback evidence. Do not run Android builds or dependency resolution on a runtime host.
