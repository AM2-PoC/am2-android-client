#!/bin/sh
set -eu

PACKAGE_ID="${1:?package ID is required}"

APP_APK="$(find app/build/outputs/apk -path '*/debug/*.apk' -type f -not -name '*androidTest*' -print -quit)"
TEST_APK="$(find app/build/outputs/apk/androidTest -path '*/debug/*.apk' -type f -print -quit)"
test -n "$APP_APK"
test -n "$TEST_APK"

wait_for_device() {
    attempts=0
    while [ "$attempts" -lt 60 ]; do
        if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && \
            adb shell true >/dev/null 2>&1 && \
            adb shell cmd package list packages >/dev/null 2>&1 && \
            adb shell settings get global device_provisioned >/dev/null 2>&1; then
            sleep 10
            return 0
        fi
        attempts=$((attempts + 1))
        sleep 5
    done
    return 1
}

install_with_retry() {
    apk="$1"
    attempts=0
    while [ "$attempts" -lt 3 ]; do
        wait_for_device
        if adb install --no-streaming -r "$apk"; then
            return 0
        fi
        attempts=$((attempts + 1))
        adb wait-for-device
        sleep 5
    done
    return 1
}

install_with_retry "$APP_APK"
adb shell monkey -p "$PACKAGE_ID" -c android.intent.category.LAUNCHER 1
install_with_retry "$TEST_APK"

OUTPUT="$(adb shell am instrument -w -r "${PACKAGE_ID}.test/androidx.test.runner.AndroidJUnitRunner")"
printf '%s\n' "$OUTPUT"
printf '%s\n' "$OUTPUT" | grep -Eq '^OK \([0-9]+ tests?\)$'
