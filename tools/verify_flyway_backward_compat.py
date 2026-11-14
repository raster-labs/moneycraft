#!/usr/bin/env python3
"""MoneyCraft 1.0.0 — Expand-and-Contract Zero-Downtime Flyway DDL Verifier.

Enforces `RELEASE_MANAGEMENT.md` Section 3.1 across:
  - `server/src/main/resources/db/authority-migration/*.sql`
  - `server/src/main/resources/db/migration/*.sql`

Rules:
  - DDL-001: Bans `DROP TABLE` and `DROP COLUMN` without `-- contract-waiver:`.
  - DDL-002: Bans `RENAME COLUMN` and `RENAME TO` without `-- contract-waiver:`.
  - DDL-003: Requires `CREATE INDEX CONCURRENTLY` (+ `-- flyway:executeInTransaction=false`) when
             indexing pre-existing tables not created in the same migration script.
  - DDL-004: Requires `SET LOCAL lock_timeout` in transactional migrations that `ALTER` pre-existing tables.
  - DDL-005: Bans blocking `ALTER COLUMN ... TYPE` and requires `NOT VALID` on `ADD CONSTRAINT ... CHECK`
             against pre-existing tables.
  - DDL-006: Bans destructive mutations (`TRUNCATE`, `DELETE FROM`, `DISABLE TRIGGER`, `DROP TRIGGER`)
             on append-only statutory/audit tables (`mc_entry`, `mc_audit_event`, `mc_edit_log_version`).
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

FLYWAY_DIRS = (
    "server/src/main/resources/db/authority-migration",
    "server/src/main/resources/db/migration",
)

APPEND_ONLY_TABLES = {"mc_entry", "mc_audit_event", "mc_edit_log_version"}

RE_CONTRACT_WAIVER = re.compile(r"--\s*contract-waiver\s*:\s*\S+", re.IGNORECASE)
RE_NON_TX = re.compile(r"--\s*flyway:executeInTransaction\s*=\s*false", re.IGNORECASE)
RE_LOCK_TIMEOUT = re.compile(r"SET\s+(?:LOCAL\s+)?lock_timeout\s*=", re.IGNORECASE)

RE_CREATE_TABLE = re.compile(
    r"\bCREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:[a-zA-Z0-9_]+\.)?([a-zA-Z0-9_]+)",
    re.IGNORECASE,
)
RE_DROP_TABLE_OR_COL = re.compile(
    r"\b(?:DROP\s+TABLE|ALTER\s+TABLE\s+[^;]*?\bDROP\s+COLUMN)\b",
    re.IGNORECASE | re.DOTALL,
)
RE_RENAME = re.compile(
    r"\bALTER\s+TABLE\s+[^;]*?\b(?:RENAME\s+COLUMN|RENAME\s+TO)\b",
    re.IGNORECASE | re.DOTALL,
)
RE_ALTER_COLUMN_TYPE = re.compile(
    r"\bALTER\s+TABLE\s+[^;]*?\bALTER\s+COLUMN\s+[a-zA-Z0-9_]+\s+(?:SET\s+DATA\s+)?TYPE\b",
    re.IGNORECASE | re.DOTALL,
)
RE_CREATE_INDEX = re.compile(
    r"\bCREATE\s+(?:UNIQUE\s+)?INDEX\s+(CONCURRENTLY\s+)?(?:IF\s+NOT\s+EXISTS\s+)?[a-zA-Z0-9_]+\s+ON\s+(?:ONLY\s+)?(?:[a-zA-Z0-9_]+\.)?([a-zA-Z0-9_]+)",
    re.IGNORECASE,
)
RE_ALTER_TABLE_TARGET = re.compile(
    r"\bALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?(?:[a-zA-Z0-9_]+\.)?([a-zA-Z0-9_]+)\s+([^;]+);",
    re.IGNORECASE | re.DOTALL,
)
RE_ADD_CHECK = re.compile(r"\bADD\s+CONSTRAINT\s+[a-zA-Z0-9_]+\s+CHECK\s*\(", re.IGNORECASE)
RE_NOT_VALID = re.compile(r"\bNOT\s+VALID\b", re.IGNORECASE)

RE_APPEND_ONLY_ABUSE = re.compile(
    r"\b(?:TRUNCATE\s+(?:TABLE\s+)?|DELETE\s+FROM\s+|UPDATE\s+)(?:[a-zA-Z0-9_]+\.)?(mc_entry|mc_audit_event|mc_edit_log_version)\b"
    r"|\bALTER\s+TABLE\s+(?:[a-zA-Z0-9_]+\.)?(mc_entry|mc_audit_event|mc_edit_log_version)\b[^;]*?\bDISABLE\s+TRIGGER\b"
    r"|\bDROP\s+TRIGGER\b[^;]*?\bON\s+(?:[a-zA-Z0-9_]+\.)?(mc_entry|mc_audit_event|mc_edit_log_version)\b",
    re.IGNORECASE | re.DOTALL,
)


@dataclass(frozen=True)
class DdlViolation:
    check_id: str
    rel_path: str
    line_no: int
    message: str


def strip_sql_comments(sql: str) -> str:
    """Replaces SQL comments with whitespace while preserving exact newline line numbers."""
    sql = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), sql, flags=re.DOTALL)
    sql = re.sub(r"--[^\n]*", "", sql)
    return sql


def line_number_at(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def check_migration_file(root: Path, sql_file: Path) -> list[DdlViolation]:
    rel_path = sql_file.relative_to(root).as_posix()
    raw_sql = sql_file.read_text(encoding="utf-8")
    has_contract_waiver = bool(RE_CONTRACT_WAIVER.search(raw_sql))
    is_non_transactional = bool(RE_NON_TX.search(raw_sql))
    clean_sql = strip_sql_comments(raw_sql)

    violations: list[DdlViolation] = []

    # Reject repeatable migrations on stateful schemas
    if sql_file.name.startswith("R__"):
        violations.append(
            DdlViolation(
                "DDL-000",
                rel_path,
                1,
                "Repeatable Flyway migrations (R__*.sql) are forbidden on stateful Authority/Business schemas.",
            )
        )

    created_tables = {m.group(1).lower() for m in RE_CREATE_TABLE.finditer(clean_sql)}

    # DDL-001: DROP TABLE / DROP COLUMN
    if not has_contract_waiver:
        for m in RE_DROP_TABLE_OR_COL.finditer(clean_sql):
            violations.append(
                DdlViolation(
                    "DDL-001",
                    rel_path,
                    line_number_at(clean_sql, m.start()),
                    "DROP TABLE / DROP COLUMN is forbidden in Expand migrations without '-- contract-waiver: <ticket>'.",
                )
            )

    # DDL-002: RENAME COLUMN / RENAME TO
    if not has_contract_waiver:
        for m in RE_RENAME.finditer(clean_sql):
            violations.append(
                DdlViolation(
                    "DDL-002",
                    rel_path,
                    line_number_at(clean_sql, m.start()),
                    "RENAME COLUMN / RENAME TO breaks N-1 runtime compatibility; use dual-write Expand pattern.",
                )
            )

    # DDL-005A: Blocking ALTER COLUMN TYPE
    if not has_contract_waiver:
        for m in RE_ALTER_COLUMN_TYPE.finditer(clean_sql):
            violations.append(
                DdlViolation(
                    "DDL-005",
                    rel_path,
                    line_number_at(clean_sql, m.start()),
                    "Blocking ALTER COLUMN ... TYPE is forbidden; add an Expand column with dual-write trigger.",
                )
            )

    # DDL-003: CREATE INDEX on pre-existing tables requires CONCURRENTLY + executeInTransaction=false
    for m in RE_CREATE_INDEX.finditer(clean_sql):
        concurrently_kw, target_table = m.group(1), m.group(2).lower()
        if target_table not in created_tables:
            if not concurrently_kw:
                violations.append(
                    DdlViolation(
                        "DDL-003",
                        rel_path,
                        line_number_at(clean_sql, m.start()),
                        f"CREATE INDEX on pre-existing table '{target_table}' must use CREATE INDEX CONCURRENTLY.",
                    )
                )
            elif not is_non_transactional:
                violations.append(
                    DdlViolation(
                        "DDL-003",
                        rel_path,
                        line_number_at(clean_sql, m.start()),
                        f"CREATE INDEX CONCURRENTLY on '{target_table}' requires header '-- flyway:executeInTransaction=false'.",
                    )
                )

    # DDL-004 & DDL-005B: ALTER TABLE on pre-existing tables requires SET LOCAL lock_timeout & NOT VALID on CHECK
    alters_existing_table = False
    for m in RE_ALTER_TABLE_TARGET.finditer(clean_sql):
        target_table = m.group(1).lower()
        alter_body = m.group(2)
        if target_table not in created_tables:
            alters_existing_table = True
            if RE_ADD_CHECK.search(alter_body) and not RE_NOT_VALID.search(alter_body):
                violations.append(
                    DdlViolation(
                        "DDL-005",
                        rel_path,
                        line_number_at(clean_sql, m.start()),
                        f"ADD CONSTRAINT ... CHECK on pre-existing table '{target_table}' must specify NOT VALID.",
                    )
                )

    if alters_existing_table and not is_non_transactional and not RE_LOCK_TIMEOUT.search(clean_sql):
        violations.append(
            DdlViolation(
                "DDL-004",
                rel_path,
                1,
                "Transactional migration altering pre-existing table(s) must set 'SET LOCAL lock_timeout = ...'.",
            )
        )

    # DDL-006: Append-only protection on mc_entry, mc_audit_event, mc_edit_log_version
    # Note: Inside trigger bodies ($$ ... $$), UPDATE/DELETE words never match unless targeting those tables directly;
    # strip dollar-quoted PL/pgSQL function bodies before checking top-level DML/DDL statements.
    top_level_sql = re.sub(
        r"\$([a-zA-Z0-9_]*)\$.*?\$\1\$",
        lambda m: re.sub(r"[^\n]", " ", m.group(0)),
        clean_sql,
        flags=re.DOTALL,
    )
    for m in RE_APPEND_ONLY_ABUSE.finditer(top_level_sql):
        tbl = next(g for g in m.groups() if g is not None)
        violations.append(
            DdlViolation(
                "DDL-006",
                rel_path,
                line_number_at(top_level_sql, m.start()),
                f"Forbidden destructive operation on append-only statutory table '{tbl}'.",
            )
        )

    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Expand-and-Contract Flyway SQL backward compatibility.")
    parser.add_argument("--root", type=Path, default=REPO_ROOT, help="Path to moneycraft-target root.")
    args = parser.parse_args()

    root = args.root.resolve()
    sql_files: list[Path] = []
    for rel_dir in FLYWAY_DIRS:
        d = root / rel_dir
        if d.is_dir():
            sql_files.extend(sorted(d.glob("*.sql")))

    violations: list[DdlViolation] = []
    for sql_file in sql_files:
        violations.extend(check_migration_file(root, sql_file))

    if violations:
        print(
            f"[FAIL] verify_flyway_backward_compat.py found {len(violations)} DDL violation(s) across {len(sql_files)} SQL file(s):",
            file=sys.stderr,
        )
        for v in violations:
            print(f"  - [{v.check_id}] {v.rel_path}:{v.line_no} -> {v.message}", file=sys.stderr)
        return 1

    print(
        f"[PASS] verify_flyway_backward_compat.py verified {len(sql_files)} Flyway SQL migration file(s) — 0 Expand-and-Contract violations."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
