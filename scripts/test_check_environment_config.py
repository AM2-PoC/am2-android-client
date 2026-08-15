#!/usr/bin/env python3
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle.kts"
WS = ROOT / "app" / "src" / "main" / "java" / "com" / "am2" / "am2" / "WebSocketManager.kt"
ABOUT = ROOT / "app" / "src" / "main" / "java" / "com" / "am2" / "am2" / "AboutActivity.kt"
WORKFLOW = ROOT / ".github" / "workflows" / "android-ci.yml"


class EnvironmentConfigTest(unittest.TestCase):
    def test_gradle_defines_exact_environment_identity(self):
        text = GRADLE.read_text()
        for token in (
            'flavorDimensions += "environment"',
            'create("dev")',
            'applicationIdSuffix = ".dev"',
            'create("staging")',
            'applicationIdSuffix = ".staging"',
            'create("production")',
            '"WEBSOCKET_URL",',
            '"UPDATE_MANIFEST_URL",',
            'buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "false")',
            'buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "true")',
        ):
            self.assertIn(token, text)
        self.assertNotIn('dimension = "trust"', text)
        self.assertNotIn('create("legacyCompat")', text)
        self.assertNotIn('create("systemTrust")', text)
        self.assertNotIn("BUNDLED_CA_ENABLED", text)

    def test_runtime_has_no_hardcoded_production_endpoint(self):
        ws = WS.read_text()
        about = ABOUT.read_text()
        self.assertIn("BuildConfig.WEBSOCKET_URL", ws)
        self.assertIn("BuildConfig.UPDATE_MANIFEST_URL", about)
        self.assertNotIn('"wss://apiapi.am2-poc.com"', ws)
        self.assertNotIn('"https://apiapi.am2-poc.com/update/version.json"', about)

        # Naming the two endpoints that existed at the time let a third one
        # through: UpdateMetadata carried the production APK URL as a literal,
        # so every build — staging included — would only accept an update
        # served from production. The rule is that no source file holds an
        # endpoint of its own, so it is checked by absence across the tree.
        offenders = sorted(
            path.relative_to(ROOT).as_posix()
            for path in (ROOT / "app/src/main/java").rglob("*.kt")
            if re.search(r'"(https|wss)://[^"]*am2-poc\.com', path.read_text())
        )
        self.assertEqual([], offenders)

        verifier = (ROOT / "app/src/main/java/com/am2/am2/update/UpdateVerifier.kt").read_text()
        self.assertIn("if (!BuildConfig.SELF_UPDATE_ENABLED) return false", verifier)
        self.assertIn("BuildConfig.APPLICATION_ID", verifier)
        self.assertNotIn('EXPECTED_PACKAGE = "com.am2.tik"', verifier)
        self.assertIn("if (!BuildConfig.SELF_UPDATE_ENABLED) return", about)
        self.assertIn("binding.btnCheckUpdate.isEnabled = BuildConfig.SELF_UPDATE_ENABLED", about)

        tls = (ROOT / "app/src/main/java/com/am2/am2/TlsCompat.kt").read_text()
        self.assertIn("sdkInt >= Build.VERSION_CODES.N", tls)
        self.assertIn("sdkInt < Build.VERSION_CODES.LOLLIPOP", tls)
        self.assertIn("applyPlatformTlsCompatibility", tls)
        self.assertIn("systemTrustManager: () -> X509TrustManager", tls)
        self.assertIn("bundledTrustManager: () -> X509TrustManager", tls)

        network = (ROOT / "app/src/main/res/xml/network_security_config.xml").read_text()
        self.assertNotIn("@raw/isrg_root_x1", network)

    def test_every_environment_declares_its_own_update_apk_url(self):
        text = GRADLE.read_text()
        for host in ("dev-api.am2-poc.com", "staging-apiapi.am2-poc.com", "apiapi.am2-poc.com"):
            self.assertIn(f'"https://{host}/update/update.apk"', text)
        self.assertEqual(3, text.count("UPDATE_APK_URL"))
        # Staging must be able to exercise the update path it now owns.
        self.assertEqual(3, text.count("SELF_UPDATE_ENABLED"))

    def test_nonproduction_urls_are_not_production_hosts(self):
        text = GRADLE.read_text()
        endpoints = {
            "dev": "dev-api.am2-poc.com",
            "staging": "staging-apiapi.am2-poc.com",
        }
        for environment, host in endpoints.items():
            self.assertIn(f'wss://{host}', text)
            self.assertIn(f'https://{host}/update/version.json', text)
        self.assertNotIn("staging-api.am2-poc.com", text)

        instrumented = (ROOT / "app/src/androidTest/java/com/am2/am2/TrustModeInstrumentedTest.kt").read_text()
        self.assertIn('BuildConfig.APPLICATION_ID.endsWith(".staging") -> "wss://staging-apiapi.am2-poc.com"', instrumented)
        self.assertNotIn("staging-api.am2-poc.com", instrumented)

    def test_ci_is_billing_aware_and_publishes_bounded_candidates(self):
        text = WORKFLOW.read_text()
        self.assertIn("github.event_name == 'pull_request'", text)
        for api in (16, 19, 25, 26, 34):
            self.assertIn(f'"api":{api}', text)
        self.assertIn("github.event.inputs.lane == 'staging'", text)
        self.assertIn("github.event.inputs.lane == 'release'", text)
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", text)
        self.assertIn('KERNEL=="kvm", GROUP="kvm", MODE="0666"', text)
        self.assertIn("build-staging-candidate:", text)
        self.assertIn("staging-compatibility:", text)
        self.assertIn("staging-artifact:", text)
        self.assertIn("assembleStagingDebug", text)
        self.assertIn("assembleStagingDebugAndroidTest", text)
        self.assertIn("mapfile -t APP_APKS", text)
        self.assertIn("mapfile -t TEST_APKS", text)
        self.assertIn('test "${#APP_APKS[@]}" -eq 1', text)
        self.assertIn('test "${#TEST_APKS[@]}" -eq 1', text)
        self.assertIn("app/build/outputs/apk/androidTest/staging/debug", text)
        self.assertIn("Require exact staging handoff contents", text)
        self.assertIn("EXPECTED_FILES=(", text)
        self.assertIn('test "${#HANDOFF_FILES[@]}" -eq "${#EXPECTED_FILES[@]}"', text)
        self.assertIn("am2-client-staging-debug.apk", text)
        self.assertIn("variant=StagingDebug", text)
        self.assertIn("supported_api=16+", text)
        self.assertIn("validated_api=16,19,25,26,34", text)
        self.assertIn("environment=staging", text)
        self.assertIn("source_commit=%s", text)
        self.assertIn("retention-days: 3", text)
        self.assertNotIn("environment: android-staging", text)
        self.assertNotIn("StagingLegacyCompat", text)
        self.assertNotIn("StagingSystemTrust", text)
        self.assertNotIn("staging-legacy", text)
        self.assertNotIn("staging-modern", text)
        staging_matrix = text.split("  staging-compatibility:", 1)[1].split("  staging-artifact:", 1)[0]
        for api in (16, 19, 25, 26, 34):
            self.assertIn(str(api), staging_matrix)
        self.assertIn("com.am2.tik.staging", staging_matrix)
        staging_job = text.split("  staging-artifact:", 1)[1].split("  release-artifact:", 1)[0]
        self.assertIn("needs: staging-compatibility", staging_job)
        self.assertNotIn("AM2_CLIENT_KEYSTORE", staging_job)
        self.assertNotIn("Production", staging_job)
        self.assertNotIn("./gradlew", staging_job)
        emulator_script = (ROOT / "scripts/run_emulator_compatibility.sh").read_text()
        self.assertIn('FIXTURE_PORT="${AM2_CI_FIXTURE_PORT:-8443}"', emulator_script)
        self.assertIn('https://10.0.2.2:$FIXTURE_PORT/', emulator_script)
        self.assertIn(
            'script: sh scripts/run_emulator_compatibility.sh "${{ matrix.package }}" staging-candidate/am2-client-staging-debug.apk staging-candidate/am2-client-staging-debug-androidTest.apk',
            text,
        )
        self.assertIn("disable-animations: false", text)
        self.assertNotIn("set -euo pipefail", emulator_script)
        self.assertIn('install_with_retry "$APP_APK"', emulator_script)
        self.assertIn('install_with_retry "$TEST_APK"', emulator_script)
        self.assertIn('APP_APK="${2:-}"', emulator_script)
        self.assertIn('TEST_APK="${3:-}"', emulator_script)
        self.assertIn("cmd package list packages", emulator_script)
        self.assertIn("settings get global device_provisioned", emulator_script)
        self.assertIn("adb install --no-streaming", emulator_script)
        self.assertLess(emulator_script.index('install_with_retry "$APP_APK"'), emulator_script.index("adb shell monkey"))
        self.assertLess(emulator_script.index("adb shell monkey"), emulator_script.index('install_with_retry "$TEST_APK"'))
        self.assertIn("adb shell am instrument", emulator_script)
        self.assertIn("grep -Eq", emulator_script)
        self.assertIn("run_instrumentation_with_timeout()", emulator_script)
        self.assertIn('SDK_INT="$(adb shell getprop ro.build.version.sdk', emulator_script)
        self.assertIn("notClass androidx.test.internal.runner.TestRequestBuilder", emulator_script)
        self.assertIn('adb shell am force-stop "${PACKAGE_ID}.test"', emulator_script)
        self.assertIn('adb shell am force-stop "$PACKAGE_ID"', emulator_script)
        self.assertIn("adb logcat -d -v time", emulator_script)
        self.assertIn('adb shell ps', emulator_script)
        self.assertIn('return 124', emulator_script)
        self.assertIn("tr -d '\\r'", emulator_script)
        self.assertLess(emulator_script.index("tr -d '\\r'"), emulator_script.index("grep -Eq"))
        self.assertIn("ProductionRelease", text)
        self.assertIn("AM2_APPROVED_SIGNER_SHA256", text)
        self.assertIn('aapt" dump badging', text)
        self.assertIn("Production release requires AM2_APPROVED_SIGNER_SHA256", GRADLE.read_text())
        instrumented = (ROOT / "app/src/androidTest/java/com/am2/am2/TrustModeInstrumentedTest.kt").read_text()
        self.assertNotIn("valid-isrgrootx1.letsencrypt.org", instrumented)
        self.assertNotIn("echo.websocket.org", instrumented)
        self.assertIn('replace("10.0.2.2", "mismatch.am2.invalid")', instrumented)
        self.assertIn('InetAddress.getByName("10.0.2.2")', instrumented)
        self.assertIn("SSLPeerUnverifiedException", instrumented)
        self.assertIn("SSLHandshakeException", instrumented)
        self.assertIn("hostnameOverride = true", instrumented)
        self.assertIn("platformTlsCompatibilitySelectsExpectedRuntimePath", instrumented)
        versions = (ROOT / "gradle/libs.versions.toml").read_text()
        self.assertIn('junitVersion = "1.1.3"', versions)
        self.assertIn('espressoCore = "3.4.0"', versions)
        self.assertNotIn("androidx.tracing:tracing", GRADLE.read_text())
        tls_compat = (ROOT / "app/src/main/java/com/am2/am2/TlsCompat.kt").read_text()
        self.assertIn("ConnectionSpec.MODERN_TLS", tls_compat)
        self.assertIn("connectionSpecs", tls_compat)
        self.assertNotIn("ConnectionSpec.COMPATIBLE_TLS", tls_compat)
        self.assertIn("LegacyTls12SocketFactory", tls_compat)
        self.assertIn('arrayOf("TLSv1.2")', tls_compat)
        self.assertIn("applyTlsConfiguration", tls_compat)
        self.assertIn("am2CiCaBase64", instrumented)
        self.assertIn("am2CiHttpsUrl", instrumented)
        self.assertIn('https://10.0.2.2:$FIXTURE_PORT/', emulator_script)
        self.assertIn("create_tls_fixture.sh", emulator_script)
        self.assertIn("start_tls_fixture", emulator_script)
        self.assertIn("stop_tls_fixture", emulator_script)
        self.assertIn("python3 scripts/test_tls_fixture.py", text)
        self.assertNotIn("DevLegacyCompat", text)
        self.assertNotIn("DevSystemTrust", text)


    def test_ci_concurrency_isolates_event_and_requested_lane(self):
        text = WORKFLOW.read_text()
        self.assertIn(
            "group: android-ci-${{ github.workflow }}-${{ github.event_name }}-${{ github.event_name == 'workflow_dispatch' && github.event.inputs.lane || github.ref }}",
            text,
        )
        self.assertNotIn("github.ref }}-${{ github.event_name }}", text)
        self.assertIn("cancel-in-progress: true", text)


if __name__ == "__main__":
    unittest.main()
