# AM2 Android Client

Canonical repository for the AM2 PTT Android client (`com.am2.tik`).

## Verification

```bash
python3 scripts/test_check_log_policy.py
python3 scripts/check_log_policy.py
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Production release remains gated on the production signing key, upgrade testing against the previous production-signed APK, and physical-device/network validation.

Source previously lived under `APK AM2/` in `AM2-PoC/AM2-Legacy`; history was preserved with `git subtree split`.
