import tempfile
import unittest
from pathlib import Path

from check_log_policy import violations


class LogPolicyTest(unittest.TestCase):
    def scan(self, relative_path: str, source: str):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            safe = root / "app/src/main/java/com/am2/am2/logging/SafeLog.kt"
            safe.parent.mkdir(parents=True)
            safe.write_text("object SafeLog {}\n", encoding="utf-8")
            path = root / relative_path
            path.parent.mkdir(parents=True)
            path.write_text(source, encoding="utf-8")
            return violations(root)

    def test_rejects_direct_android_logging_and_stack_traces(self):
        findings = self.scan(
            "app/src/main/java/example/Bad.kt",
            "import android.util.Log\nfun bad(e: Exception) { Log.e(\"T\", \"x\"); e.printStackTrace() }\n",
        )
        self.assertEqual(2, len(findings))

    def test_rejects_console_printing(self):
        findings = self.scan(
            "app/src/main/java/example/Bad.kt",
            'fun bad() { println("secret") }\n',
        )
        self.assertEqual(1, len(findings))

    def test_rejects_http_body_or_header_logging(self):
        findings = self.scan(
            "app/src/main/java/example/Bad.kt",
            "val level = HttpLoggingInterceptor.Level.BODY\n",
        )
        self.assertEqual(1, len(findings))

    def test_fake_safe_logger_does_not_bypass_policy(self):
        findings = self.scan(
            "app/src/main/java/example/logging/SafeLog.kt",
            "import android.util.Log\nobject SafeLog { fun e() = Log.e(\"T\", \"unsafe\") }\n",
        )
        self.assertEqual(1, len(findings))
        self.assertIn("direct android.util.Log call", findings[0])


if __name__ == "__main__":
    unittest.main()
