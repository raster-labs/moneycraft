#!/usr/bin/env python3
"""MoneyCraft 1.0.0 — Progressive Stage Runner (`EXEC-PLAN-070`).

Executes mandatory hygiene/compliance verifiers followed by the Maven reactor build
for the requested delivery gate:
  --stage reader       (Gate G1: Standalone .1800 binary reader JAR only)
  --stage foundation   (Gate G2-F: Dual-PG18 Foundation, Auth, RBAC, Tink, Multi-Company)
  --stage accounting   (Gate G2-J: CoA 28 Groups, Masters, 24 Voucher Types, Double-Entry)
  --stage import       (Gate G2-I: .1800 Staging/Promotion & XML/JSONEx/Excel Exchange)
  --stage full         (Gate G3: Full Monorepo Build & Integration Suites)
  --stage dual-war-qa  (Gate G4: Identical WAR Qualification on Tomcat 11 & WildFly 42)
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
TOOLS_DIR = Path(__file__).resolve().parent

VALID_STAGES = ("reader", "foundation", "accounting", "import", "full", "dual-war-qa")

DEFAULT_MAC_JAVA_HOME = Path("/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home")
DEFAULT_MAC_MVN = Path("/opt/homebrew/opt/maven/bin/mvn")


def resolve_java_home() -> str:
    env_java = os.environ.get("JAVA_HOME")
    if env_java and Path(env_java).is_dir():
        return env_java
    if DEFAULT_MAC_JAVA_HOME.is_dir():
        return str(DEFAULT_MAC_JAVA_HOME)
    java_bin = shutil.which("java")
    if java_bin:
        return str(Path(java_bin).resolve().parent.parent)
    raise RuntimeError("JDK 25 JAVA_HOME could not be located.")


def resolve_mvn_bin() -> str:
    if DEFAULT_MAC_MVN.is_file():
        return str(DEFAULT_MAC_MVN)
    mvn_bin = shutil.which("mvn")
    if mvn_bin:
        return mvn_bin
    raise RuntimeError("Apache Maven 3.10+ executable ('mvn') could not be located.")


def run_step(label: str, cmd: list[str], env: dict[str, str]) -> None:
    print(f"\n==> [{label}] {' '.join(cmd)}", flush=True)
    result = subprocess.run(cmd, cwd=REPO_ROOT, env=env, check=False)
    if result.returncode != 0:
        print(f"[FAIL] Step '{label}' exited with code {result.returncode}.", file=sys.stderr)
        sys.exit(result.returncode)


def main() -> int:
    parser = argparse.ArgumentParser(description="MoneyCraft progressive stage builder and gate verifier.")
    parser.add_argument(
        "--stage",
        choices=VALID_STAGES,
        default="full",
        help="Target Maven profile stage to build and verify.",
    )
    parser.add_argument(
        "--skip-tests",
        action="store_true",
        help="Compile and package stage artifacts without running unit/integration tests.",
    )
    parser.add_argument(
        "--hygiene-only",
        action="store_true",
        help="Run manifest, Flyway Expand-and-Contract, and zero-licensing gates only.",
    )
    args, extra_mvn_args = parser.parse_known_args()

    env = {**os.environ, "JAVA_HOME": resolve_java_home()}
    py = sys.executable

    run_step("Hygiene 1/3: Manifest & Zero-Stub Gate", [py, str(TOOLS_DIR / "verify_manifest.py")], env)
    run_step("Hygiene 2/3: Flyway Expand-and-Contract Gate", [py, str(TOOLS_DIR / "verify_flyway_backward_compat.py")], env)
    run_step("Hygiene 3/3: AT-GEN-003 Zero-Licensing Gate", [py, str(TOOLS_DIR / "check_zero_licensing.py")], env)

    if args.hygiene_only:
        print("\n[PASS] All hygiene gates passed.")
        return 0

    mvn = resolve_mvn_bin()
    goal = "package" if args.skip_tests else ("test" if args.stage == "reader" else "verify")
    mvn_cmd = [mvn, "-B", f"-P{args.stage}", goal]
    if args.skip_tests:
        mvn_cmd.extend(["-DskipTests", "-DskipITs=true"])
    mvn_cmd.extend(extra_mvn_args)

    run_step(f"Maven Stage '{args.stage}'", mvn_cmd, env)
    print(f"\n[PASS] Stage '{args.stage}' completed cleanly.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
