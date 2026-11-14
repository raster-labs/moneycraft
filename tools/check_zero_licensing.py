#!/usr/bin/env python3
"""MoneyCraft 1.0.0 — AT-GEN-003 & SEC-015 Zero-Licensing / Air-Gapped Verifier.

Scans `moneycraft-target/` (`pom.xml`, `reader`, `server`, `ui`, `qa`, `docker`, `deploy`, `deployment`)
to guarantee zero:
  1. License-key validators, activation servers, hardware dongle checks, or trial expirations.
  2. Artificial seat, user, company, or voucher volume caps.
  3. Proprietary `.lic` file readers/parsers (`SEC-015`).
  4. External font CDNs (`fonts.googleapis.com`, `fonts.gstatic.com`, `use.typekit.net`).
  5. Outbound analytics or telemetry beacons (`google-analytics`, `googletagmanager`, `mixpanel`, `sentry.io`, `segment.io`, `posthog`).
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
    "ui",
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
}

FORBIDDEN_RULES: tuple[tuple[str, re.Pattern[str], str], ...] = (
    (
        "GEN-003-LICENSE-VALIDATOR",
        re.compile(
            r"\b(?:LicenseValidator|LicenseKeyValidator|validateLicenseKey|verifyLicenseKey|"
            r"ActivationServerClient|checkHardwareDongle|TrialExpirationGuard|isTrialExpired)\b"
        ),
        "Software-key licensing or trial-expiration routine is strictly forbidden.",
    ),
    (
        "GEN-003-ARTIFICIAL-CAP",
        re.compile(
            r"\b(?:MAX_LICENSED_SEATS|MAX_LICENSED_COMPANIES|MAX_LICENSED_VOUCHERS|"
            r"enforceSeatLimit|enforceCompanyLimit|enforceVoucherCap)\b"
        ),
        "Artificial seat, company, or voucher volume caps are strictly forbidden.",
    ),
    (
        "SEC-015-LIC-PARSER",
        re.compile(
            r"""\b(?:LicenseFileParser|parseLicenseFile|readLicenseFile|decodeLicenseFile)\b"""
            r"""|(?:tally\.lic\b)"""
            r"""|(?<!endsWith\()(['"][^'"]+[\w-]+\.lic['"])""",
            re.IGNORECASE,
        ),
        "Parsing or loading proprietary .lic license files is strictly forbidden.",
    ),
    (
        "GEN-003-EXTERNAL-FONT-CDN",
        re.compile(
            r"(?:fonts\.googleapis\.com|fonts\.gstatic\.com|use\.typekit\.net|brick\.freetls\.fastly\.net)",
            re.IGNORECASE,
        ),
        "External font CDNs violate offline-first Private-LAN autonomy; bundle WOFF2/TTF locally.",
    ),
    (
        "GEN-003-TELEMETRY-BEACON",
        re.compile(
            r"(?:google-analytics\.com|googletagmanager\.com|api\.segment\.io|api\.mixpanel\.com|"
            r"ingest\.sentry\.io|app\.posthog\.com|script\.hotjar\.com)",
            re.IGNORECASE,
        ),
        "External telemetry/analytics beacons are strictly forbidden.",
    ),
)


@dataclass(frozen=True)
class LicensingViolation:
    rule_id: str
    rel_path: str
    line_no: int
    reason: str
    snippet: str


def iter_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for rel_target in SCAN_TARGETS:
        target = root / rel_target
        if not target.exists():
            continue
        if target.is_file():
            files.append(target)
            continue
        for dirpath, dirnames, filenames in os.walk(target):
            dirnames[:] = [d for d in sorted(dirnames) if d not in IGNORED_DIRS]
            for fname in sorted(filenames):
                fpath = Path(dirpath) / fname
                if fpath.suffix.lower() in BINARY_SUFFIXES:
                    continue
                files.append(fpath)
    return files


def scan_file(root: Path, fpath: Path) -> list[LicensingViolation]:
    rel_posix = fpath.relative_to(root).as_posix()
    # Skip ArchUnit / governance test files that assert the absence of these strings by name if marked
    try:
        text = fpath.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return []

    is_test_source = "/src/test/" in f"/{rel_posix}"
    violations: list[LicensingViolation] = []
    for idx, line in enumerate(text.splitlines(), start=1):
        if "zero-licensing-test-allow" in line:
            continue
        for rule_id, pattern, reason in FORBIDDEN_RULES:
            if rule_id == "SEC-015-LIC-PARSER" and is_test_source:
                continue
            if pattern.search(line):
                violations.append(
                    LicensingViolation(
                        rule_id=rule_id,
                        rel_path=rel_posix,
                        line_no=idx,
                        reason=reason,
                        snippet=line.strip()[:140],
                    )
                )
    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description="AT-GEN-003 Zero-Licensing, Zero-CDN & Zero-Telemetry Verifier.")
    parser.add_argument("--root", type=Path, default=REPO_ROOT, help="Path to moneycraft-target root.")
    args = parser.parse_args()

    root = args.root.resolve()
    files = iter_files(root)
    violations: list[LicensingViolation] = []

    # Also verify zero .lic files exist anywhere inside moneycraft-target
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in IGNORED_DIRS]
        for fname in filenames:
            if fname.lower().endswith(".lic"):
                rel = (Path(dirpath) / fname).relative_to(root).as_posix()
                violations.append(
                    LicensingViolation("SEC-015-LIC-FILE", rel, 1, "Forbidden .lic file present in repository.", fname)
                )

    for fpath in files:
        violations.extend(scan_file(root, fpath))

    if violations:
        print(f"[FAIL] AT-GEN-003 check_zero_licensing.py found {len(violations)} violation(s):", file=sys.stderr)
        for v in violations:
            print(f"  - [{v.rule_id}] {v.rel_path}:{v.line_no} -> {v.reason} ({v.snippet})", file=sys.stderr)
        return 1

    print(
        f"[PASS] AT-GEN-003 check_zero_licensing.py verified {len(files)} files — "
        "0 license keys, 0 seat/voucher caps, 0 .lic parsers, 0 external CDNs, 0 telemetry beacons."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
