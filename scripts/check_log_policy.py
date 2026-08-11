#!/usr/bin/env python3
"""Fail closed when Android code bypasses the sanctioned logging facade."""

import re
import sys
from pathlib import Path

FORBIDDEN = (
    (re.compile(r"(?:\bandroid\.util\.)?\bLog\s*\.[A-Za-z_][A-Za-z0-9_]*\s*\("), "direct android.util.Log call"),
    (re.compile(r"\.printStackTrace\s*\("), "printStackTrace call"),
    (re.compile(r"\b(?:System\.(?:out|err)\.)?(?:print|println|printf)\s*\("), "direct console printing"),
    (re.compile(r"HttpLoggingInterceptor\.Level\.(?:BODY|HEADERS)"), "credential-bearing HTTP logging level"),
)
SAFE_LOG_REQUIRED = (
    "if (!BuildConfig.DEBUG) return",
    "error.javaClass.simpleName",
)
SAFE_LOG_FORBIDDEN = (
    "error.message",
    "localizedMessage",
    "stackTraceToString",
)


def violations(root: Path) -> list[str]:
    source_root = root / "app" / "src"
    safe_logger = source_root / "main/java/com/am2/am2/logging/SafeLog.kt"
    findings: list[str] = []
    if not source_root.exists():
        return [f"missing Android source root: {source_root}"]
    if not safe_logger.is_file():
        return [f"missing sanctioned logging facade: {safe_logger}"]

    safe_text = safe_logger.read_text(encoding="utf-8", errors="replace")
    for required in SAFE_LOG_REQUIRED:
        if required not in safe_text:
            findings.append(f"{safe_logger.relative_to(root)}: missing release-safe facade guard: {required}")
    for forbidden in SAFE_LOG_FORBIDDEN:
        if forbidden in safe_text:
            findings.append(f"{safe_logger.relative_to(root)}: unsafe throwable detail in facade: {forbidden}")

    for path in sorted(source_root.rglob("*")):
        if path.suffix not in {".kt", ".java"} or path == safe_logger:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for line_number, line in enumerate(text.splitlines(), 1):
            for pattern, description in FORBIDDEN:
                if pattern.search(line):
                    findings.append(f"{path.relative_to(root)}:{line_number}: {description}")
    return findings


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    findings = violations(root)
    if findings:
        print("Android logging policy violations:", file=sys.stderr)
        print("\n".join(findings), file=sys.stderr)
        return 1
    print("Android logging policy: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
