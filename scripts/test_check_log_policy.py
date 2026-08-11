import tempfile
import unittest
from pathlib import Path

from check_log_policy import violations


class LogPolicyTest(unittest.TestCase):
    def scan(self, relative_path: str, source: str, safe_source: str | None = None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            safe = root / "app/src/main/java/com/am2/am2/logging/SafeLog.kt"
            safe.parent.mkdir(parents=True)
            safe.write_text(
                safe_source
                or "if (!BuildConfig.DEBUG) return\nerror.javaClass.simpleName\n",
                encoding="utf-8",
            )
            path = root / relative_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            return violations(root)

    def test_rejects_direct_logging_stack_traces_and_console_output(self):
        findings = self.scan(
            "app/src/main/java/example/Bad.kt",
            "fun bad(e: Exception) { android.util.Log.wtf(\"T\", \"x\"); e.printStackTrace(); System.err.println(\"x\") }\n",
        )
        self.assertEqual(3, len(findings))

    def test_scans_non_main_source_sets(self):
        findings = self.scan(
            "app/src/debug/java/example/Bad.kt",
            "fun bad() { Log.e(\"T\", \"secret\") }\n",
        )
        self.assertEqual(1, len(findings))

    def test_rejects_http_body_or_header_logging(self):
        findings = self.scan(
            "app/src/release/java/example/Bad.kt",
            "val level = HttpLoggingInterceptor.Level.BODY\n",
        )
        self.assertEqual(1, len(findings))

    def test_rejects_unsafe_sanctioned_facade(self):
        findings = self.scan(
            "app/src/main/java/example/Good.kt",
            "fun good() = Unit\n",
            safe_source="error.javaClass.simpleName\nerror.message\n",
        )
        self.assertTrue(any("missing release-safe facade guard" in item for item in findings))
        self.assertTrue(any("unsafe throwable detail" in item for item in findings))


if __name__ == "__main__":
    unittest.main()
