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
SAFE_METHOD = re.compile(
    r"fun\s+(d|i|w|e)\s*\([^)]*\)\s*\{(.*?)\n\s*\}",
    re.DOTALL,
)
SAFE_METHODS = {"d", "i", "w", "e"}
SAFE_LOG_CALL = re.compile(r"\bLog\s*\.([A-Za-z_][A-Za-z0-9_]*)\s*\(")
SAFE_GUARD = re.compile(r"^\s*if\s*\(\s*!BuildConfig\.DEBUG\s*\)\s*return\s*$", re.MULTILINE)
SAFE_THROWABLE_DETAILS = re.compile(
    r"(?:error|throwable|exception|cause)\s*(?:\?\.)?\.\s*"
    r"(?:message|localizedMessage|stackTraceToString|toString)\b|"
    r"\$(?:\{\s*(?:error|throwable|exception|cause)\s*\}|"
    r"(?:error|throwable|exception|cause)\b(?!\s*[.?]))",
    re.IGNORECASE,
)


def facade_violations(path: Path, root: Path) -> list[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    rel = path.relative_to(root)
    findings: list[str] = []
    methods = {name: body for name, body in SAFE_METHOD.findall(text)}
    missing = sorted(SAFE_METHODS - methods.keys())
    for name in missing:
        findings.append(f"{rel}: missing sanctioned facade method: {name}")
    for name, body in methods.items():
        guard = SAFE_GUARD.search(body)
        calls = list(SAFE_LOG_CALL.finditer(body))
        if guard is None:
            findings.append(f"{rel}: SafeLog.{name} missing executable debug guard")
        if len(calls) != 1 or calls[0].group(1) != name:
            findings.append(f"{rel}: SafeLog.{name} must contain exactly one Log.{name} call")
        elif guard is not None and guard.start() > calls[0].start():
            findings.append(f"{rel}: SafeLog.{name} debug guard must precede logging")
        if SAFE_THROWABLE_DETAILS.search(body):
            findings.append(f"{rel}: SafeLog.{name} exposes throwable detail")
    outside_methods = SAFE_METHOD.sub("", text)
    if SAFE_THROWABLE_DETAILS.search(outside_methods):
        findings.append(f"{rel}: exposes throwable detail outside sanctioned methods")
    if SAFE_LOG_CALL.search(outside_methods):
        findings.append(f"{rel}: logging call outside sanctioned methods")
    return findings


def violations(root: Path) -> list[str]:
    source_root = root / "app" / "src"
    safe_logger = source_root / "main/java/com/am2/am2/logging/SafeLog.kt"
    findings: list[str] = []
    if not source_root.exists():
        return [f"missing Android source root: {source_root}"]
    if not safe_logger.is_file():
        return [f"missing sanctioned logging facade: {safe_logger}"]

    findings.extend(facade_violations(safe_logger, root))
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
