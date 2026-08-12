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
                or '''
                    import android.util.Log
                    object SafeLog {
                        fun d(tag: String, message: String) {
                            if (!BuildConfig.DEBUG) return
                            Log.d(tag, message)
                        }
                        fun i(tag: String, message: String) {
                            if (!BuildConfig.DEBUG) return
                            Log.i(tag, message)
                        }
                        fun w(tag: String, message: String) {
                            if (!BuildConfig.DEBUG) return
                            Log.w(tag, message)
                        }
                        fun e(tag: String, message: String) {
                            if (!BuildConfig.DEBUG) return
                            Log.e(tag, message)
                        }
                    }
                ''',
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
        self.assertTrue(any("missing sanctioned facade method" in item for item in findings))
        self.assertTrue(any("exposes throwable detail" in item for item in findings))

    def test_rejects_facade_with_comment_guard_and_unguarded_log(self):
        findings = self.scan(
            "app/src/main/java/example/Good.kt",
            "fun good() = Unit\n",
            safe_source='''
                import android.util.Log
                // if (!BuildConfig.DEBUG) return
                // error.javaClass.simpleName
                object SafeLog {
                    fun e(tag: String, error: Throwable) {
                        Log.e(tag, "$error")
                    }
                }
            ''',
        )
        self.assertTrue(findings)

    def test_rejects_facade_when_only_one_method_is_guarded(self):
        findings = self.scan(
            "app/src/main/java/example/Good.kt",
            "fun good() = Unit\n",
            safe_source='''
                import android.util.Log
                object SafeLog {
                    fun d(tag: String, message: String) {
                        if (!BuildConfig.DEBUG) return
                        Log.d(tag, message)
                    }
                    fun e(tag: String, error: Throwable) {
                        Log.e(tag, error.javaClass.simpleName)
                    }
                }
            ''',
        )
        self.assertTrue(any("SafeLog.e missing executable debug guard" in item for item in findings))

    def test_rejects_unsafe_duplicate_facade_overload(self):
        findings = self.scan(
            "app/src/main/java/example/Good.kt",
            "fun good() = Unit\n",
            safe_source='''
                import android.util.Log
                object SafeLog {
                    fun d(tag: String, message: String) {
                        if (!BuildConfig.DEBUG) return
                        Log.d(tag, message)
                    }
                    fun i(tag: String, message: String) {
                        if (!BuildConfig.DEBUG) return
                        Log.i(tag, message)
                    }
                    fun w(tag: String, message: String) {
                        if (!BuildConfig.DEBUG) return
                        Log.w(tag, message)
                    }
                    fun e(tag: String, error: Throwable) {
                        Log.e(tag, "$error")
                    }
                    fun e(tag: String, message: String) {
                        if (!BuildConfig.DEBUG) return
                        Log.e(tag, message)
                    }
                }
            ''',
        )
        self.assertTrue(any("duplicate sanctioned facade method: e" in item for item in findings))
        self.assertTrue(any("SafeLog.e missing executable debug guard" in item for item in findings))
        self.assertTrue(any("SafeLog.e exposes throwable detail" in item for item in findings))

    def test_rejects_facade_logging_outside_sanctioned_methods(self):
        findings = self.scan(
            "app/src/main/java/example/Good.kt",
            "fun good() = Unit\n",
            safe_source='''
                import android.util.Log
                object SafeLog {
                    init { Log.e("T", "secret") }
                    fun d(tag: String, message: String) { if (!BuildConfig.DEBUG) return; Log.d(tag, message) }
                    fun i(tag: String, message: String) { if (!BuildConfig.DEBUG) return; Log.i(tag, message) }
                    fun w(tag: String, message: String) { if (!BuildConfig.DEBUG) return; Log.w(tag, message) }
                    fun e(tag: String, message: String) { if (!BuildConfig.DEBUG) return; Log.e(tag, message) }
                }
            ''',
        )
        self.assertTrue(any("outside sanctioned methods" in item for item in findings))


if __name__ == "__main__":
    unittest.main()
