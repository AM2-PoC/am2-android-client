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

run_instrumentation_with_timeout() {
    output_file="${TMPDIR:-/tmp}/am2-instrumentation.$$.log"
    rm -f "$output_file"

    adb shell am instrument -w -r \
        "${PACKAGE_ID}.test/androidx.test.runner.AndroidJUnitRunner" \
        >"$output_file" 2>&1 &
    instrument_pid=$!

    elapsed=0
    while kill -0 "$instrument_pid" 2>/dev/null; do
        if [ "$elapsed" -ge 120 ]; then
            adb shell am force-stop "${PACKAGE_ID}.test" >/dev/null 2>&1 || true
            adb shell am force-stop "$PACKAGE_ID" >/dev/null 2>&1 || true
            kill "$instrument_pid" 2>/dev/null || true
            wait "$instrument_pid" 2>/dev/null || true
            printf '%s\n' "Instrumentation timed out after ${elapsed}s" >&2
            cat "$output_file" >&2
            rm -f "$output_file"
            return 124
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done

    instrument_status=0
    wait "$instrument_pid" || instrument_status=$?
    cat "$output_file"
    test "$instrument_status" -eq 0
    grep -Eq '^OK \([0-9]+ tests?\)$' "$output_file"
    rm -f "$output_file"
}

install_with_retry "$APP_APK"
adb shell monkey -p "$PACKAGE_ID" -c android.intent.category.LAUNCHER 1
install_with_retry "$TEST_APK"
run_instrumentation_with_timeout
