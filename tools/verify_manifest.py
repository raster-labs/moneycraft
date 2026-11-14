#!/usr/bin/env python3
"""MoneyCraft 1.0.0 — Zero-Stub, Real-PG18 & Exact-Decimal Manifest Verifier (QUAL-001..005).

Scans `moneycraft-target/` (`reader`, `server`, `ui/src`, `qa`, `docker`, `deploy`, `deployment`, `pom.xml`)
and fails closed (exit 1) on:
  1. Placeholder markers (`TODO`, `FIXME`, `XXX`) without an explicit `manifest-waiver:` annotation.
  2. Stub `UnsupportedOperationException` usages without an explicit `manifest-waiver:` annotation.
  3. Silenced tests (`@Disabled`, `test.skip`, `it.skip`, `describe.skip`, `xit`, `xdescribe`) without `gate-waiver:`.
  4. Forbidden H2 or SQLite test dependencies / JDBC URLs (PostgreSQL 18 Testcontainers is mandatory).
  5. `float`/`Float`/`double`/`Double` fields or parameters in `accounting`, `inventory`, `statutory`,
     `banking`, `reporting`, or `exchange` Java packages (`MCJ1-DATA-01`).
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

SCAN_TARGETS = (
    "pom.xml",
    "reader",
    "server",
    "ui/src",
    "qa",
    "docker",
    "deploy",
    "deployment",
)

IGNORED_DIRS = {
    ".git",
    "target",
    "node_modules",
    "dist",
    ".angular",
    "coverage",
    "__pycache__",
    ".pytest_cache",
    ".ruff_cache",
}

BINARY_SUFFIXES = {
    ".woff2",
    ".woff",
    ".ttf",
    ".otf",
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".webp",
    ".ico",
    ".pdf",
    ".jar",
    ".war",
    ".class",
    ".zip",
    ".gz",
    ".br",
    ".zst",
    ".1800",
    ".tsf",
    ".p12",
    ".jks",
}

PLACEHOLDER_TOKEN = "|".join(("TO" + "DO", "FIX" + "ME", "X" + "XX"))
RE_PLACEHOLDER = re.compile(rf"\b({PLACEHOLDER_TOKEN})\b")
RE_UNSUPPORTED_OP = re.compile(r"\bUnsupportedOperationException\b")
RE_DISABLED_JAVA = re.compile(r"@Disabled\b")
RE_SKIPPED_TS = re.compile(r"\b(?:test\.skip|it\.skip|describe\.skip|xit|xdescribe)\s*\(")
RE_FORBIDDEN_DB = re.compile(
    r"(?:com\.h2database|org\.h2\.|jdbc:h2:|sqlite-jdbc|org\.sqlite\.|jdbc:sqlite:)"
)

MONEY_DOMAIN_PATH_RE = re.compile(
    r"server/src/main/java/com/raster/moneycraft/"
    r"(?:accounting|inventory|statutory|banking|reporting|exchange)/.*\.java$"
)
RE_JAVA_FLOAT_DOUBLE = re.compile(
    r"(?<![\w$.])\b(float|Float|double|Double)\b(?:\s*\[\s*\])?\s+[a-zA-Z_$][\w$]*"
)

WAIVER_MARKERS = ("manifest-waiver:", "gate-waiver:")


@dataclass(frozen=True)
class Violation:
    rule_id: str
    rel_path: str
    line_no: int
    detail: str


def has_waiver(lines: list[str], idx: int) -> bool:
    current = lines[idx]
    prev = lines[idx - 1] if idx > 0 else ""
    combined = f"{prev}\n{current}"
    return any(marker in combined for marker in WAIVER_MARKERS)


def strip_java_comments_and_strings(line: str, in_block_comment: bool) -> tuple[str, bool]:
    out: list[str] = []
    i = 0
    n = len(line)
    in_str = False
    in_char = False
    while i < n:
        ch = line[i]
        nxt = line[i + 1] if i + 1 < n else ""
        if in_block_comment:
            if ch == "*" and nxt == "/":
                in_block_comment = False
                i += 2
            else:
                i += 1
            continue
        if in_str:
            if ch == "\\" and i + 1 < n:
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if in_char:
            if ch == "\\" and i + 1 < n:
                i += 2
                continue
            if ch == "'":
                in_char = False
            i += 1
            continue
        if ch == "/" and nxt == "/":
            break
        if ch == "/" and nxt == "*":
            in_block_comment = True
            i += 2
            continue
        if ch == '"':
            in_str = True
            out.append('""')
            i += 1
            continue
        if ch == "'":
            in_char = True
            out.append("''")
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out), in_block_comment


def iter_target_files(root: Path) -> list[Path]:
    collected: list[Path] = []
    for rel_target in SCAN_TARGETS:
        target = root / rel_target
        if not target.exists():
            continue
        if target.is_file():
            collected.append(target)
            continue
        for dirpath, dirnames, filenames in os.walk(target):
            dirnames[:] = [d for d in sorted(dirnames) if d not in IGNORED_DIRS]
            for fname in sorted(filenames):
                fpath = Path(dirpath) / fname
                if fpath.suffix.lower() in BINARY_SUFFIXES:
                    continue
                collected.append(fpath)
    return collected


def scan_file(root: Path, path: Path) -> list[Violation]:
    rel_posix = path.relative_to(root).as_posix()
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return []

    lines = content.splitlines()
    violations: list[Violation] = []
    is_java = path.suffix == ".java"
    is_ts_js = path.suffix in {".ts", ".tsx", ".js", ".mjs"}
    is_money_domain_java = bool(MONEY_DOMAIN_PATH_RE.search(rel_posix))
    in_block_comment = False

    for idx, raw_line in enumerate(lines):
        line_no = idx + 1

        # 1. Placeholder markers (QUAL-001)
        m_ph = RE_PLACEHOLDER.search(raw_line)
        if m_ph and not has_waiver(lines, idx):
            violations.append(
                Violation(
                    "QUAL-001-PLACEHOLDER",
                    rel_posix,
                    line_no,
                    f"Forbidden placeholder token '{m_ph.group(1)}': {raw_line.strip()}",
                )
            )

        # 2. Forbidden H2 / SQLite substitutes (QUAL-002)
        m_db = RE_FORBIDDEN_DB.search(raw_line)
        if m_db and not has_waiver(lines, idx):
            violations.append(
                Violation(
                    "QUAL-002-DB-SUBSTITUTE",
                    rel_posix,
                    line_no,
                    f"Forbidden H2/SQLite dependency or URL '{m_db.group(0)}' (use PostgreSQL 18).",
                )
            )

        if is_java:
            code_only, in_block_comment = strip_java_comments_and_strings(raw_line, in_block_comment)

            # 3. UnsupportedOperationException in Java source
            if RE_UNSUPPORTED_OP.search(code_only) and not has_waiver(lines, idx):
                violations.append(
                    Violation(
                        "QUAL-001-STUB-EXCEPTION",
                        rel_posix,
                        line_no,
                        f"Unwaived UnsupportedOperationException: {raw_line.strip()}",
                    )
                )

            # 4. @Disabled without explicit gate-waiver
            if RE_DISABLED_JAVA.search(code_only) and not has_waiver(lines, idx):
                violations.append(
                    Violation(
                        "QUAL-005-DISABLED-TEST",
                        rel_posix,
                        line_no,
                        "@Disabled test without explicit gate-waiver comment.",
                    )
                )

            # 5. Floating-point prohibition in financial/inventory/statutory domains (MCJ1-DATA-01)
            if is_money_domain_java:
                m_fp = RE_JAVA_FLOAT_DOUBLE.search(code_only)
                if m_fp and not has_waiver(lines, idx):
                    violations.append(
                        Violation(
                            "MCJ1-DATA-01-FLOAT",
                            rel_posix,
                            line_no,
                            f"Forbidden '{m_fp.group(1)}' in exact-decimal domain package: {raw_line.strip()}",
                        )
                    )

        if is_ts_js:
            if RE_SKIPPED_TS.search(raw_line) and not has_waiver(lines, idx):
                violations.append(
                    Violation(
                        "QUAL-005-SKIPPED-SPEC",
                        rel_posix,
                        line_no,
                        f"Skipped frontend test without explicit gate-waiver: {raw_line.strip()}",
                    )
                )

    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify MoneyCraft zero-stub & exact-decimal manifest rules.")
    parser.add_argument("--root", type=Path, default=REPO_ROOT, help="Path to moneycraft-target root.")
    args = parser.parse_args()

    root = args.root.resolve()
    files = iter_target_files(root)
    all_violations: list[Violation] = []
    for fpath in files:
        all_violations.extend(scan_file(root, fpath))

    if all_violations:
        print(f"[FAIL] verify_manifest.py found {len(all_violations)} violation(s) across {len(files)} files:", file=sys.stderr)
        for v in all_violations:
            print(f"  - [{v.rule_id}] {v.rel_path}:{v.line_no} -> {v.detail}", file=sys.stderr)
        return 1

    print(f"[PASS] verify_manifest.py scanned {len(files)} files — 0 stubs, 0 H2/SQLite refs, 0 float/double violations.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
