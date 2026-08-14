#!/bin/sh
set -eu

PACKAGE_ID="${1:?package ID is required}"
FIXTURE_DIR="${TMPDIR:-/tmp}/am2-tls-fixture.$$"
FIXTURE_PID=""

stop_tls_fixture() {
    if [ -n "$FIXTURE_PID" ]; then
        kill "$FIXTURE_PID" 2>/dev/null || true
        wait "$FIXTURE_PID" 2>/dev/null || true
    fi
    rm -rf "$FIXTURE_DIR"
}

start_tls_fixture() {
    sh scripts/create_tls_fixture.sh "$FIXTURE_DIR"
    python3 scripts/tls_fixture_server.py \
        --cert "$FIXTURE_DIR/server.crt" \
        --key "$FIXTURE_DIR/server.key" \
        --port 8443 >"$FIXTURE_DIR/server.log" 2>&1 &
    FIXTURE_PID=$!
    attempts=0
    while [ "$attempts" -lt 30 ]; do
        if curl --silent --show-error --fail \
            --cacert "$FIXTURE_DIR/ca.crt" \
            --connect-timeout 2 https://127.0.0.1:8443/health >/dev/null 2>&1; then
            return 0
        fi
        if ! kill -0 "$FIXTURE_PID" 2>/dev/null; then
            cat "$FIXTURE_DIR/server.log" >&2
            return 1
        fi
        attempts=$((attempts + 1))
        sleep 1
    done
    cat "$FIXTURE_DIR/server.log" >&2
    return 1
}

trap stop_tls_fixture EXIT HUP INT TERM

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

    SDK_INT="$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
    CI_CA_BASE64="$(base64 "$FIXTURE_DIR/ca.crt" | tr -d '\r\n')"
    set -- \
        -e am2CiCaBase64 "$CI_CA_BASE64" \
        -e am2CiHttpsUrl "https://10.0.2.2:8443/"
    if [ "$SDK_INT" -lt 21 ]; then
        set -- "$@" -e notClass androidx.test.internal.runner.TestRequestBuilder
    fi

    adb shell am instrument -w -r "$@" \
        "${PACKAGE_ID}.test/androidx.test.runner.AndroidJUnitRunner" \
        >"$output_file" 2>&1 &
    instrument_pid=$!

    elapsed=0
    while kill -0 "$instrument_pid" 2>/dev/null; do
        if [ "$elapsed" -ge 120 ]; then
            printf '%s\n' "Instrumentation timed out after ${elapsed}s" >&2
            cat "$output_file" >&2
            printf '%s\n' "--- instrumentation process ---" >&2
            adb shell ps 2>/dev/null | grep -E "(${PACKAGE_ID}|androidx.test)" >&2 || true
            printf '%s\n' "--- logcat crash/runtime diagnostics ---" >&2
            adb logcat -d -v time 2>/dev/null | grep -E -i -A30 -B10 \
                "FATAL EXCEPTION|AndroidRuntime|ClassNotFoundException|NoClassDefFoundError|VerifyError|Process ${PACKAGE_ID}|INSTRUMENTATION" >&2 || true
            adb shell am force-stop "${PACKAGE_ID}.test" >/dev/null 2>&1 || true
            adb shell am force-stop "$PACKAGE_ID" >/dev/null 2>&1 || true
            kill "$instrument_pid" 2>/dev/null || true
            wait "$instrument_pid" 2>/dev/null || true
            rm -f "$output_file"
            return 124
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done

    instrument_status=0
    wait "$instrument_pid" || instrument_status=$?
    # Old Android instrumentation writes CRLF even on a Linux runner. Normalize
    # before matching the terminal JUnit summary without changing diagnostics.
    tr -d '\r' < "$output_file" > "${output_file}.normalized"
    mv "${output_file}.normalized" "$output_file"
    cat "$output_file"
    test "$instrument_status" -eq 0
    grep -Eq '^OK \([0-9]+ tests?\)$' "$output_file"
    rm -f "$output_file"
}

start_tls_fixture
install_with_retry "$APP_APK"
adb shell monkey -p "$PACKAGE_ID" -c android.intent.category.LAUNCHER 1
install_with_retry "$TEST_APK"
run_instrumentation_with_timeout
