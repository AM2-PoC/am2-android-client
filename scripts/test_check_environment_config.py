#!/usr/bin/env python3
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
            'flavorDimensions += listOf("environment", "trust")',
            'create("dev")',
            'applicationIdSuffix = ".dev"',
            'create("staging")',
            'applicationIdSuffix = ".staging"',
            'create("production")',
            '"WEBSOCKET_URL",',
            '"UPDATE_MANIFEST_URL",',
            'buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "false")',
            'buildConfigField("Boolean", "SELF_UPDATE_ENABLED", "true")',
            'create("legacyCompat")',
            'create("systemTrust")',
            'buildConfigField("Boolean", "BUNDLED_CA_ENABLED", "true")',
        ):
            self.assertIn(token, text)

    def test_runtime_has_no_hardcoded_production_endpoint(self):
        ws = WS.read_text()
        about = ABOUT.read_text()
        self.assertIn("BuildConfig.WEBSOCKET_URL", ws)
        self.assertIn("BuildConfig.UPDATE_MANIFEST_URL", about)
        self.assertNotIn('"wss://apiapi.am2-poc.com"', ws)
        self.assertNotIn('"https://apiapi.am2-poc.com/update/version.json"', about)

        verifier = (ROOT / "app/src/main/java/com/am2/am2/update/UpdateVerifier.kt").read_text()
        self.assertIn("if (!BuildConfig.SELF_UPDATE_ENABLED) return false", verifier)
        self.assertIn("BuildConfig.APPLICATION_ID", verifier)
        self.assertNotIn('EXPECTED_PACKAGE = "com.am2.tik"', verifier)
        self.assertIn("if (!BuildConfig.SELF_UPDATE_ENABLED) return", about)
        self.assertIn("binding.btnCheckUpdate.isEnabled = BuildConfig.SELF_UPDATE_ENABLED", about)

        tls = (ROOT / "app/src/main/java/com/am2/am2/TlsCompat.kt").read_text()
        self.assertIn("!BuildConfig.BUNDLED_CA_ENABLED", tls)

        network = (ROOT / "app/src/main/res/xml/network_security_config.xml").read_text()
        self.assertNotIn("@raw/isrg_root_x1", network)

    def test_nonproduction_urls_are_not_production_hosts(self):
        text = GRADLE.read_text()
        for environment in ("dev", "staging"):
            self.assertIn(f'wss://{environment}-api.am2-poc.com', text)
            self.assertIn(f'https://{environment}-api.am2-poc.com/update/version.json', text)

    def test_ci_is_billing_aware_and_uploads_only_release_artifacts(self):
        text = WORKFLOW.read_text()
        self.assertIn("github.event_name == 'pull_request'", text)
        for api in (16, 19, 25, 26, 34):
            self.assertIn(f'"api":{api}', text)
        self.assertIn("github.event.inputs.lane == 'release'", text)
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", text)
        self.assertIn('KERNEL=="kvm", GROUP="kvm", MODE="0666"', text)
        self.assertEqual(1, text.count("actions/upload-artifact@v4"))
        self.assertIn("retention-days: 3", text)
        emulator_script = (ROOT / "scripts/run_emulator_compatibility.sh").read_text()
        self.assertIn('script: sh scripts/run_emulator_compatibility.sh "${{ matrix.package }}"', text)
        self.assertIn("disable-animations: false", text)
        self.assertNotIn("set -euo pipefail", emulator_script)
        self.assertIn('install_with_retry "$APP_APK"', emulator_script)
        self.assertIn('install_with_retry "$TEST_APK"', emulator_script)
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
        self.assertIn("ProductionSystemTrust", text)
        self.assertIn("AM2_APPROVED_SIGNER_SHA256", text)
        self.assertIn('aapt" dump badging', text)
        self.assertIn("Production release requires AM2_APPROVED_SIGNER_SHA256", GRADLE.read_text())
        instrumented = (ROOT / "app/src/androidTest/java/com/am2/am2/TrustModeInstrumentedTest.kt").read_text()
        self.assertIn("valid-isrgrootx1.letsencrypt.org", instrumented)
        self.assertIn("wss://echo.websocket.org/", instrumented)
        versions = (ROOT / "gradle/libs.versions.toml").read_text()
        self.assertIn('junitVersion = "1.1.3"', versions)
        self.assertIn('espressoCore = "3.4.0"', versions)
        self.assertNotIn("androidx.tracing:tracing", GRADLE.read_text())
        tls_compat = (ROOT / "app/src/main/java/com/am2/am2/TlsCompat.kt").read_text()
        self.assertIn("ConnectionSpec.MODERN_TLS", tls_compat)
        self.assertIn("connectionSpecs", tls_compat)
        self.assertNotIn("ConnectionSpec.COMPATIBLE_TLS", tls_compat)
        nightly_matrix = text.split("github.event.inputs.lane == 'nightly'", 1)[1].split("startsWith(github.ref", 1)[0]
        self.assertIn("DevLegacyCompat", nightly_matrix)
        self.assertNotIn("StagingLegacyCompat", nightly_matrix)


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
