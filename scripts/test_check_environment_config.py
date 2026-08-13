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
        for api in (16, 19, 25, 26, 35):
            self.assertIn(f'"api":{api}', text)
        self.assertIn("github.event.inputs.lane == 'release'", text)
        self.assertIn("startsWith(github.ref, 'refs/tags/v')", text)
        self.assertEqual(1, text.count("actions/upload-artifact@v4"))
        self.assertIn("retention-days: 3", text)


if __name__ == "__main__":
    unittest.main()
