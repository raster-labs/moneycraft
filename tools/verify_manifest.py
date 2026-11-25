#!/usr/bin/env python3
"""MoneyCraft 1.0.0 — Confinement, Phase-Manifest & Zero-Stub Hygiene Verifier (B02 / QUAL-001..005).

Supports two complementary verification contracts in `moneycraft-target/`:
  A. Default Hygiene Gate (`python3 tools/verify_manifest.py` — invoked by `tools/build.py`):
     Scans `pom.xml`, `reader`, `server`, `desktop`, `ui/src`, `qa`, `docker`, `deploy`, and `deployment`
     and fails closed (exit 1) on:
       1. Placeholder markers (`TO`+`DO`, `FIX`+`ME`, `X`+`XX`) without `manifest-waiver:`.
       2. Stub `UnsupportedOperationException` usages without `manifest-waiver:`.
       3. Silenced tests (`@Disabled`, `test.skip`, `it.skip`, `describe.skip`, `xit`, `xdescribe`) without `gate-waiver:`.
       4. Forbidden H2 or SQLite test dependencies / JDBC URLs (PostgreSQL 18 Testcontainers mandatory).
       5. `float`/`Float`/`double`/`Double` fields or parameters in `accounting`, `inventory`, `statutory`,
          `banking`, `reporting`, `exchange`, or `extension` Java packages (`MCJ1-DATA-01`).

  B. G0-BOOT-050 Confinement & Phase Manifest Checker (`Doc 17 §1.1` / `Doc 41` row `B02`, stdlib-only):
     - `python3 tools/verify_manifest.py --self-test`:
       Executes deterministic in-memory confinement self-tests (valid, traversal, duplicate,
       conflicting-action, wrong-hash, and generated-output-as-source rejection) without mutating
       any source or evidence tree, plus symlink inspection of the bound repository root.
     - `python3 tools/verify_manifest.py --phase <phase> --mode <before|after>`:
       Verifies `execution/phases/<phase>/files.json` (and `execution/records.json` in `after` mode)
       against actual canonical repository paths, symlink-free parent chains, proven absence or
       exact SHA-256 digests, and generated-output exclusion rules.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent

SCAN_TARGETS = (
    "pom.xml",
    "reader",
    "server",
    "desktop",
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
FORBIDDEN_DB_TOKEN = "|".join(
    (
        r"com\.h2" + "database",
        r"org\.h2" + r"\.",
        "jdbc:" + "h2:",
        "sqlite-" + "jdbc",
        r"org\.sqlite" + r"\.",
        "jdbc:" + "sqlite:",
    )
)
FORBIDDEN_JDBC_URL_TOKEN = "|".join(("jdbc:" + "h2:", "jdbc:" + "sqlite:"))
RE_FORBIDDEN_DB = re.compile(rf"(?:{FORBIDDEN_DB_TOKEN})")
RE_FORBIDDEN_JDBC_URL = re.compile(rf"(?:{FORBIDDEN_JDBC_URL_TOKEN})")

MONEY_DOMAIN_PATH_RE = re.compile(
    r"server/src/main/java/com/raster/moneycraft/"
    r"(?:accounting|inventory|statutory|banking|reporting|exchange|extension)/.*\.java$"
)
RE_JAVA_FLOAT_DOUBLE = re.compile(
    r"(?<![\w$.])\b(float|Float|double|Double)\b(?:\s*\[\s*\])?\s+[a-zA-Z_$][\w$]*"
)

WAIVER_MARKERS = ("manifest-waiver:", "gate-waiver:")

RE_HEX64 = re.compile(r"^[0-9a-f]{64}$")
ABSENCE_SENTINEL = "NOT_APPLICABLE_AFTER_PROVEN_ABSENCE"
VALID_ACTIONS = {"Create", "Modify", "Delete"}
REQUIRED_ROW_FIELDS = (
    "rowId",
    "exactPath",
    "action",
    "purpose",
    "requirementIds",
    "acceptanceCaseIds",
    "designRecordRefs",
    "dependsOn",
    "verificationRefs",
    "beforeSha256",
    "expectedResult",
)

GENERATED_OUTPUT_SEGMENTS = {
    "target",
    "dist",
    "node_modules",
    ".angular",
    "coverage",
    "__pycache__",
    ".pytest_cache",
    ".ruff_cache",
    ".git",
}
GENERATED_OUTPUT_SUFFIXES = {
    ".class",
    ".jar",
    ".war",
    ".pyc",
    ".pyo",
}


@dataclass(frozen=True)
class Violation:
    rule_id: str
    rel_path: str
    line_no: int
    detail: str


@dataclass(frozen=True)
class ManifestIssue:
    code: str
    subject: str
    detail: str


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


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

        code_only = ""
        if is_java:
            code_only, in_block_comment = strip_java_comments_and_strings(raw_line, in_block_comment)

        # 2. Forbidden H2 / SQLite substitutes (QUAL-002)
        m_db = (
            (RE_FORBIDDEN_DB.search(code_only) or RE_FORBIDDEN_JDBC_URL.search(raw_line))
            if is_java
            else RE_FORBIDDEN_DB.search(raw_line)
        )
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

            # 5. Floating-point prohibition in financial/inventory/statutory/extension domains (MCJ1-DATA-01)
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


def validate_canonical_source_path(rel_path: Any) -> list[ManifestIssue]:
    if not isinstance(rel_path, str) or not rel_path:
        return [ManifestIssue("MANIFEST-PATH-TRAVERSAL", str(rel_path), "exactPath must be a non-empty string.")]

    if (
        rel_path.startswith("/")
        or rel_path.endswith("/")
        or "\\" in rel_path
        or "\x00" in rel_path
        or "//" in rel_path
        or re.match(r"^[A-Za-z]:", rel_path)
    ):
        return [
            ManifestIssue(
                "MANIFEST-PATH-TRAVERSAL",
                rel_path,
                f"Non-canonical, absolute, or backslash-containing path '{rel_path}' is forbidden.",
            )
        ]

    segments = rel_path.split("/")
    for seg in segments:
        if seg in {"", ".", ".."}:
            return [
                ManifestIssue(
                    "MANIFEST-PATH-TRAVERSAL",
                    rel_path,
                    f"Traversal or dot segment '{seg}' in '{rel_path}' is forbidden.",
                )
            ]

    for seg in segments[:-1]:
        if seg in GENERATED_OUTPUT_SEGMENTS:
            return [
                ManifestIssue(
                    "MANIFEST-GENERATED-OUTPUT-AS-SOURCE",
                    rel_path,
                    f"Generated/build output directory '{seg}' cannot be admitted as a source manifest row.",
                )
            ]
    leaf = segments[-1]
    if leaf in GENERATED_OUTPUT_SEGMENTS or Path(leaf).suffix.lower() in GENERATED_OUTPUT_SUFFIXES:
        return [
            ManifestIssue(
                "MANIFEST-GENERATED-OUTPUT-AS-SOURCE",
                rel_path,
                f"Generated/compiled artifact '{rel_path}' cannot be admitted as a source manifest row.",
            )
        ]

    return []


def verify_manifest_in_memory(
    manifest_doc: dict[str, Any],
    mode: str,
    virtual_fs: dict[str, str],
    records_index: dict[str, str] | None = None,
) -> list[ManifestIssue]:
    """Validate a phase manifest document in memory without touching the filesystem."""
    issues: list[ManifestIssue] = []
    if mode not in {"before", "after"}:
        issues.append(ManifestIssue("MANIFEST-INVALID-MODE", mode, "mode must be 'before' or 'after'."))
        return issues

    rows = manifest_doc.get("rows")
    if not isinstance(rows, list) or not rows:
        issues.append(ManifestIssue("MANIFEST-SCHEMA-INVALID", "rows", "Manifest 'rows' must be a non-empty list."))
        return issues

    seen_row_ids: set[str] = set()
    seen_paths: dict[str, str] = {}
    records_map = records_index or {}

    for idx, row in enumerate(rows):
        if not isinstance(row, dict):
            issues.append(ManifestIssue("MANIFEST-SCHEMA-INVALID", f"rows[{idx}]", "Row must be an object."))
            continue

        missing = [field for field in REQUIRED_ROW_FIELDS if field not in row]
        if missing:
            issues.append(
                ManifestIssue(
                    "MANIFEST-SCHEMA-INVALID",
                    str(row.get("rowId", f"rows[{idx}]")),
                    f"Missing required field(s): {', '.join(missing)}",
                )
            )
            continue

        row_id = str(row["rowId"])
        exact_path = row["exactPath"]
        action = row["action"]
        before_sha = row["beforeSha256"]

        if row_id in seen_row_ids:
            issues.append(
                ManifestIssue("MANIFEST-DUPLICATE-ENTRY", row_id, f"Duplicate rowId '{row_id}' in manifest.")
            )
        seen_row_ids.add(row_id)

        path_issues = validate_canonical_source_path(exact_path)
        if path_issues:
            issues.extend(path_issues)
            continue

        if action not in VALID_ACTIONS:
            issues.append(
                ManifestIssue(
                    "MANIFEST-CONFLICTING-ACTION",
                    exact_path,
                    f"Unsupported action '{action}' on '{exact_path}'.",
                )
            )
            continue

        if exact_path in seen_paths:
            prev_action = seen_paths[exact_path]
            if prev_action == action:
                issues.append(
                    ManifestIssue(
                        "MANIFEST-DUPLICATE-ENTRY",
                        exact_path,
                        f"Duplicate exactPath '{exact_path}' with action '{action}'.",
                    )
                )
            else:
                issues.append(
                    ManifestIssue(
                        "MANIFEST-CONFLICTING-ACTION",
                        exact_path,
                        f"Conflicting actions '{prev_action}' vs '{action}' for '{exact_path}'.",
                    )
                )
            continue
        seen_paths[exact_path] = action

        if action == "Create":
            if before_sha != ABSENCE_SENTINEL:
                issues.append(
                    ManifestIssue(
                        "MANIFEST-CONFLICTING-ACTION",
                        exact_path,
                        f"Create row '{row_id}' must use '{ABSENCE_SENTINEL}' for beforeSha256.",
                    )
                )
                continue
        else:
            if not isinstance(before_sha, str) or not RE_HEX64.match(before_sha):
                issues.append(
                    ManifestIssue(
                        "MANIFEST-CONFLICTING-ACTION",
                        exact_path,
                        f"{action} row '{row_id}' requires a 64-hex beforeSha256 digest.",
                    )
                )
                continue

        actual_sha = virtual_fs.get(exact_path)
        if mode == "before":
            if action == "Create":
                if actual_sha is not None:
                    issues.append(
                        ManifestIssue(
                            "MANIFEST-CONFLICTING-ACTION",
                            exact_path,
                            f"Create row '{row_id}' target '{exact_path}' already exists; automatic Create->Modify is forbidden.",
                        )
                    )
            else:
                if actual_sha is None:
                    issues.append(
                        ManifestIssue(
                            "MANIFEST-CONFLICTING-ACTION",
                            exact_path,
                            f"{action} row '{row_id}' target '{exact_path}' is absent before write.",
                        )
                    )
                elif actual_sha != before_sha:
                    issues.append(
                        ManifestIssue(
                            "MANIFEST-HASH-MISMATCH",
                            exact_path,
                            f"beforeSha256 mismatch on '{exact_path}': expected {before_sha}, got {actual_sha}.",
                        )
                    )
        else:
            if action in {"Create", "Modify"}:
                if actual_sha is None:
                    issues.append(
                        ManifestIssue(
                            "MANIFEST-HASH-MISMATCH",
                            exact_path,
                            f"{action} row '{row_id}' target '{exact_path}' does not exist in after mode.",
                        )
                    )
                    continue
                expected_after = row.get("afterSha256") or records_map.get(row_id) or records_map.get(exact_path)
                if expected_after is not None:
                    if not isinstance(expected_after, str) or not RE_HEX64.match(expected_after):
                        issues.append(
                            ManifestIssue(
                                "MANIFEST-HASH-MISMATCH",
                                exact_path,
                                f"Invalid recorded afterSha256 '{expected_after}' for '{exact_path}'.",
                            )
                        )
                    elif actual_sha != expected_after:
                        issues.append(
                            ManifestIssue(
                                "MANIFEST-HASH-MISMATCH",
                                exact_path,
                                f"Post-write SHA-256 mismatch on '{exact_path}': expected {expected_after}, got {actual_sha}.",
                            )
                        )
            elif action == "Delete":
                if actual_sha is not None:
                    issues.append(
                        ManifestIssue(
                            "MANIFEST-CONFLICTING-ACTION",
                            exact_path,
                            f"Delete row '{row_id}' target '{exact_path}' still exists in after mode.",
                        )
                    )

    return issues


def check_root_and_paths_for_symlinks(root: Path, rel_paths: list[str]) -> list[ManifestIssue]:
    issues: list[ManifestIssue] = []
    cur = Path(root.anchor)
    for part in root.parts[1:]:
        cur = cur / part
        if cur.is_symlink():
            issues.append(
                ManifestIssue(
                    "MANIFEST-SYMLINK-REJECTED",
                    str(cur),
                    f"Repository root or parent '{cur}' is a symbolic link.",
                )
            )
    if issues:
        return issues

    for rel in rel_paths:
        if validate_canonical_source_path(rel):
            continue
        probe = root
        for seg in rel.split("/"):
            probe = probe / seg
            if probe.is_symlink():
                issues.append(
                    ManifestIssue(
                        "MANIFEST-SYMLINK-REJECTED",
                        rel,
                        f"Repository path component '{probe}' is a symbolic link.",
                    )
                )
                break
    return issues


def _base_row(
    row_id: str,
    exact_path: str,
    action: str,
    before_sha: str = ABSENCE_SENTINEL,
    after_sha: str | None = None,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "rowId": row_id,
        "exactPath": exact_path,
        "action": action,
        "purpose": f"Synthetic self-test row {row_id}",
        "requirementIds": ["GEN-006", "QUAL-002"],
        "acceptanceCaseIds": ["AT-GEN-006", "AT-QUAL-002"],
        "designRecordRefs": ["EXEC-PLAN-070"],
        "dependsOn": [],
        "verificationRefs": ["V-MANIFEST"],
        "beforeSha256": before_sha,
        "expectedResult": "Self-test verification only",
    }
    if after_sha is not None:
        row["afterSha256"] = after_sha
    return row


def run_self_test(root: Path) -> int:
    """Run G0-BOOT-050 in-memory confinement self-tests (zero real tree writes)."""
    h_a = hashlib.sha256(b"alpha-before").hexdigest()
    h_a_after = hashlib.sha256(b"alpha-after").hexdigest()
    h_b_after = hashlib.sha256(b"bravo-created").hexdigest()
    h_wrong = hashlib.sha256(b"tampered-bytes").hexdigest()

    failures: list[str] = []

    # 1. Valid manifest (before & after)
    valid_doc = {
        "phase": "self-test-valid",
        "rows": [
            _base_row("E01", "execution/context.json", "Create"),
            _base_row("B02", "tools/verify_manifest.py", "Modify", before_sha=h_a),
        ],
    }
    issues_before = verify_manifest_in_memory(valid_doc, "before", {"tools/verify_manifest.py": h_a})
    issues_after = verify_manifest_in_memory(
        valid_doc,
        "after",
        {"execution/context.json": h_b_after, "tools/verify_manifest.py": h_a_after},
        records_index={"E01": h_b_after, "B02": h_a_after},
    )
    if issues_before or issues_after:
        failures.append(f"Case 1 (valid) unexpectedly failed: {issues_before + issues_after}")

    # 2. Traversal / non-canonical path rejection
    for bad_path in ("../escape.json", "/etc/passwd", "execution/./context.json", "execution\\context.json"):
        trav_doc = {"phase": "self-test-traversal", "rows": [_base_row("T01", bad_path, "Create")]}
        res = verify_manifest_in_memory(trav_doc, "before", {})
        if not any(i.code == "MANIFEST-PATH-TRAVERSAL" for i in res):
            failures.append(f"Case 2 (traversal) did not reject '{bad_path}': {res}")

    # 3. Duplicate path / rowId rejection
    dup_doc = {
        "phase": "self-test-duplicate",
        "rows": [
            _base_row("E01", "execution/context.json", "Create"),
            _base_row("E02", "execution/context.json", "Create"),
        ],
    }
    res_dup = verify_manifest_in_memory(dup_doc, "before", {})
    if not any(i.code == "MANIFEST-DUPLICATE-ENTRY" for i in res_dup):
        failures.append(f"Case 3 (duplicate) did not reject duplicate exactPath: {res_dup}")

    # 4. Conflicting action & Create-on-existing rejection
    conflict_doc = {
        "phase": "self-test-conflict",
        "rows": [
            _base_row("E01", "execution/context.json", "Create"),
            _base_row("E02", "execution/context.json", "Modify", before_sha=h_a),
        ],
    }
    res_conflict = verify_manifest_in_memory(conflict_doc, "before", {})
    if not any(i.code == "MANIFEST-CONFLICTING-ACTION" for i in res_conflict):
        failures.append(f"Case 4a (conflicting-action) did not reject Create+Modify: {res_conflict}")

    create_existing_doc = {
        "phase": "self-test-create-existing",
        "rows": [_base_row("E01", "execution/context.json", "Create")],
    }
    res_create_existing = verify_manifest_in_memory(
        create_existing_doc, "before", {"execution/context.json": h_a}
    )
    if not any(i.code == "MANIFEST-CONFLICTING-ACTION" for i in res_create_existing):
        failures.append(f"Case 4b (create-on-existing) did not reject existing target: {res_create_existing}")

    # 5. Wrong-hash rejection (before & after)
    wrong_hash_doc = {
        "phase": "self-test-wrong-hash",
        "rows": [_base_row("B02", "tools/verify_manifest.py", "Modify", before_sha=h_a)],
    }
    res_wrong_before = verify_manifest_in_memory(
        wrong_hash_doc, "before", {"tools/verify_manifest.py": h_wrong}
    )
    res_wrong_after = verify_manifest_in_memory(
        wrong_hash_doc,
        "after",
        {"tools/verify_manifest.py": h_wrong},
        records_index={"B02": h_a_after},
    )
    if not any(i.code == "MANIFEST-HASH-MISMATCH" for i in res_wrong_before):
        failures.append(f"Case 5a (wrong-hash before) did not reject mismatched beforeSha256: {res_wrong_before}")
    if not any(i.code == "MANIFEST-HASH-MISMATCH" for i in res_wrong_after):
        failures.append(f"Case 5b (wrong-hash after) did not reject mismatched afterSha256: {res_wrong_after}")

    # 6. Generated-output-as-source rejection
    for gen_path in (
        "server/target/classes/com/raster/moneycraft/App.class",
        "server/target/moneycraft.war",
        "ui/dist/moneycraft/browser/main.js",
        "ui/node_modules/zone.js/index.js",
    ):
        gen_doc = {"phase": "self-test-generated", "rows": [_base_row("G01", gen_path, "Create")]}
        res_gen = verify_manifest_in_memory(gen_doc, "before", {})
        if not any(i.code == "MANIFEST-GENERATED-OUTPUT-AS-SOURCE" for i in res_gen):
            failures.append(f"Case 6 (generated-output-as-source) did not reject '{gen_path}': {res_gen}")

    # 7. Independent root/parent symlink check (read-only)
    symlink_issues = check_root_and_paths_for_symlinks(root, ["tools/verify_manifest.py"])
    if symlink_issues:
        failures.append(f"Case 7 (root-symlink-check) found symlink issue(s): {symlink_issues}")

    if failures:
        print(f"[FAIL] verify_manifest.py --self-test failed {len(failures)} check(s):", file=sys.stderr)
        for msg in failures:
            print(f"  - {msg}", file=sys.stderr)
        return 1

    print(
        "[PASS] verify_manifest.py --self-test passed all 7 in-memory confinement checks "
        "(valid, traversal, duplicate, conflicting-action, wrong-hash, generated-output-as-source, root-symlinks)."
    )
    return 0


def load_records_sha_map(records_path: Path) -> dict[str, str]:
    if not records_path.is_file():
        return {}
    doc = json.loads(records_path.read_text(encoding="utf-8"))
    out: dict[str, str] = {}
    for rec in doc.get("records", []):
        if not isinstance(rec, dict):
            continue
        if rec.get("selfHashExempt") is True:
            continue
        sha = rec.get("sha256")
        if isinstance(sha, str) and RE_HEX64.match(sha):
            if isinstance(rec.get("recordId"), str):
                out[rec["recordId"]] = sha
            if isinstance(rec.get("exactPath"), str):
                out[rec["exactPath"]] = sha
    return out


def run_phase_check(root: Path, phase: str, mode: str) -> int:
    if not re.match(r"^[a-z0-9][a-z0-9_-]{0,63}$", phase):
        print(f"[FAIL] Invalid phase identifier '{phase}'.", file=sys.stderr)
        return 1

    manifest_rel = f"execution/phases/{phase}/files.json"
    manifest_path = root / manifest_rel
    if not manifest_path.is_file():
        print(f"[FAIL] Phase manifest '{manifest_rel}' not found under {root}.", file=sys.stderr)
        return 1

    try:
        manifest_doc = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"[FAIL] Invalid JSON in '{manifest_rel}': {exc}", file=sys.stderr)
        return 1

    rows = manifest_doc.get("rows", [])
    rel_paths = [r.get("exactPath", "") for r in rows if isinstance(r, dict)]
    symlink_issues = check_root_and_paths_for_symlinks(root, [manifest_rel, *rel_paths])
    if symlink_issues:
        print(f"[FAIL] Symlink confinement check failed for phase '{phase}':", file=sys.stderr)
        for iss in symlink_issues:
            print(f"  - [{iss.code}] {iss.subject} -> {iss.detail}", file=sys.stderr)
        return 1

    virtual_fs: dict[str, str] = {}
    for rel in rel_paths:
        if validate_canonical_source_path(rel):
            continue
        candidate = root / rel
        if candidate.exists():
            if not candidate.is_file():
                print(f"[FAIL] Manifest target '{rel}' exists on disk as a non-regular file.", file=sys.stderr)
                return 1
            virtual_fs[rel] = sha256_file(candidate)

    records_map = load_records_sha_map(root / "execution/records.json") if mode == "after" else {}
    issues = verify_manifest_in_memory(manifest_doc, mode, virtual_fs, records_index=records_map)
    if issues:
        print(
            f"[FAIL] verify_manifest.py --phase {phase} --mode {mode} found {len(issues)} issue(s):",
            file=sys.stderr,
        )
        for iss in issues:
            print(f"  - [{iss.code}] {iss.subject} -> {iss.detail}", file=sys.stderr)
        return 1

    if mode == "after":
        hygiene_violations: list[Violation] = []
        for rel in virtual_fs:
            hygiene_violations.extend(scan_file(root, root / rel))
        if hygiene_violations:
            print(
                f"[FAIL] Phase '{phase}' post-write hygiene scan found {len(hygiene_violations)} violation(s):",
                file=sys.stderr,
            )
            for v in hygiene_violations:
                print(f"  - [{v.rule_id}] {v.rel_path}:{v.line_no} -> {v.detail}", file=sys.stderr)
            return 1

    print(
        f"[PASS] verify_manifest.py --phase {phase} --mode {mode} verified {len(rows)} manifest row(s) "
        f"under {root} (0 confinement, digest, or stub violations)."
    )
    return 0


def run_hygiene_scan(root: Path) -> int:
    files = iter_target_files(root)
    all_violations: list[Violation] = []
    for fpath in files:
        all_violations.extend(scan_file(root, fpath))

    if all_violations:
        print(
            f"[FAIL] verify_manifest.py found {len(all_violations)} violation(s) across {len(files)} files:",
            file=sys.stderr,
        )
        for v in all_violations:
            print(f"  - [{v.rule_id}] {v.rel_path}:{v.line_no} -> {v.detail}", file=sys.stderr)
        return 1

    print(
        f"[PASS] verify_manifest.py scanned {len(files)} files — 0 stubs, 0 H2/SQLite refs, 0 float/double violations."
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify MoneyCraft zero-stub hygiene and EXEC-PLAN-070 / G0-BOOT-050 phase manifests."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=REPO_ROOT,
        help="Path to moneycraft-target root (defaults to parent of tools/).",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run stdlib in-memory confinement & digest self-tests (Doc 41 B02).",
    )
    parser.add_argument(
        "--phase",
        type=str,
        default=None,
        help="Phase directory name under execution/phases/<phase>/files.json (e.g., g0-bootstrap).",
    )
    parser.add_argument(
        "--mode",
        choices=("before", "after"),
        default=None,
        help="Verification mode when --phase is specified ('before' or 'after').",
    )
    args = parser.parse_args()

    root = args.root.resolve(strict=False)

    if args.self_test:
        return run_self_test(root)

    if args.phase is not None or args.mode is not None:
        if not args.phase or not args.mode:
            print("[FAIL] Both --phase and --mode ('before' or 'after') must be supplied together.", file=sys.stderr)
            return 1
        return run_phase_check(root, args.phase, args.mode)

    return run_hygiene_scan(root)


if __name__ == "__main__":
    sys.exit(main())
