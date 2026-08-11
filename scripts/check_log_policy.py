#!/usr/bin/env python3
"""Fail closed when Android code bypasses the sanitized logging facade."""

import re
import sys
from pathlib import Path

FORBIDDEN = (
    (re.compile(r"\bLog\s*\.[vdiewtf]\s*\("), "direct android.util.Log call"),
    (re.compile(r"\.printStackTrace\s*\("), "printStackTrace call"),
    (re.compile(r"\b(?:print|println)\s*\("), "direct console printing"),
    (
        re.compile(r"HttpLoggingInterceptor\.Level\.(?:BODY|HEADERS)"),
        "credential-bearing HTTP logging level",
    ),
)


def violations(root: Path) -> list[str]:
    source_root = root / "app" / "src" / "main"
    safe_logger = source_root / "java" / "com" / "am2" / "am2" / "logging" / "SafeLog.kt"
    findings: list[str] = []
    if not source_root.exists():
        return [f"missing Android source root: {source_root}"]
    if not safe_logger.is_file():
        return [f"missing sanctioned logging facade: {safe_logger}"]

    for path in sorted(source_root.rglob("*")):
        if path.suffix not in {".kt", ".java"} or path == safe_logger:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for line_number, line in enumerate(text.splitlines(), 1):
            for pattern, description in FORBIDDEN:
                if pattern.search(line):
                    findings.append(
                        f"{path.relative_to(root)}:{line_number}: {description}"
                    )
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
