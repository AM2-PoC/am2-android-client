#!/bin/sh
set -eu

PACKAGE_ID="${1:?package ID is required}"

APP_APK="$(find app/build/outputs/apk -path '*/debug/*.apk' -type f -not -name '*androidTest*' -print -quit)"
TEST_APK="$(find app/build/outputs/apk/androidTest -path '*/debug/*.apk' -type f -print -quit)"
test -n "$APP_APK"
test -n "$TEST_APK"

adb install -r "$APP_APK"
adb shell monkey -p "$PACKAGE_ID" -c android.intent.category.LAUNCHER 1
adb install -r "$TEST_APK"

OUTPUT="$(adb shell am instrument -w -r "${PACKAGE_ID}.test/androidx.test.runner.AndroidJUnitRunner")"
printf '%s\n' "$OUTPUT"
printf '%s\n' "$OUTPUT" | grep -Eq '^OK \([0-9]+ tests?\)$'
