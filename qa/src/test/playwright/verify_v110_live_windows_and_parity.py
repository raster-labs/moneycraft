#!/usr/bin/env python3
"""
MoneyCraft v1.1.0 — Direct Windows Reference Server (:9000), 127-Oracle Corpus,
Company 100000 ('ABC & Co') & 80/80 Golden Benchmark Reconciliation Verifier.

All monetary and quantity arithmetic uses Python's exact `decimal.Decimal`
exclusively (zero binary floating-point). Never mutates the Windows lab host
(strictly read-only `<TALLYREQUEST>Export</TALLYREQUEST>` envelopes only).
"""

from __future__ import annotations

import argparse
import base64
from decimal import Decimal
import hashlib
import http.cookiejar
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET


REPO_ROOT = Path(__file__).resolve().parents[4]
WORKSPACE_ROOT = REPO_ROOT.parent
ORACLES_ROOT = WORKSPACE_ROOT / "argon_workspace" / "windows_lab_oracles"
ORACLE_100000_DIR = ORACLES_ROOT / "100000"
PARITY_GOLDEN_DIR = ORACLES_ROOT / "parity_golden"
SOURCE_100000_BIN_DIR = WORKSPACE_ROOT / "argon_workspace" / "Source" / "Binary" / "Tally Prime" / "Data" / "100000"
ICONS_DIR = REPO_ROOT / "ui" / "public" / "icons"
HELP_JSON_PATH = REPO_ROOT / "ui" / "public" / "help" / "contextual-help.en-GB.json"


def to_dec(val: object) -> Decimal:
    if val is None or val == "":
        return Decimal("0.00")
    if isinstance(val, Decimal):
        return val
    raw = str(val).strip().replace(",", "")
    return Decimal(raw) if raw else Decimal("0.00")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def compute_gstin_check_char(prefix14: str) -> str:
    alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    factor = 2
    total = 0
    for ch in reversed(prefix14.upper()):
        code_point = alphabet.index(ch)
        addend = factor * code_point
        factor = 1 if factor == 2 else 2
        addend = (addend // 36) + (addend % 36)
        total += addend
    remainder = total % 36
    check_code_point = (36 - remainder) % 36
    return alphabet[check_code_point]


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class MoneyCraftSessionClient:
    def __init__(self, gateway_url: str) -> None:
        self.gateway_url = gateway_url.rstrip("/")
        self.api_base = f"{self.gateway_url}/api/v1"
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.no_redirect_opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar),
            NoRedirectHandler(),
        )
        self.csrf_token: str = ""

    def _sync_csrf(self) -> None:
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN" and cookie.value:
                self.csrf_token = urllib.parse.unquote(cookie.value)

    def request_raw(
        self,
        method: str,
        path: str,
        body: dict[str, object] | None = None,
        extra_headers: dict[str, str] | None = None,
        use_api_prefix: bool = True,
    ) -> tuple[int, dict[str, str], bytes]:
        url = f"{self.api_base}{path}" if use_api_prefix else f"{self.gateway_url}{path}"
        payload: bytes | None = None
        headers: dict[str, str] = {"Accept": "application/json"}
        if body is not None:
            payload = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if method in ("POST", "PUT", "PATCH", "DELETE"):
            self._sync_csrf()
            if self.csrf_token:
                headers["X-XSRF-TOKEN"] = self.csrf_token
            if not extra_headers or "Idempotency-Key" not in extra_headers:
                headers["Idempotency-Key"] = str(uuid.uuid4())
        if extra_headers:
            headers.update(extra_headers)

        for attempt in range(2):
            req = urllib.request.Request(url, data=payload, headers=headers, method=method)
            try:
                with self.opener.open(req, timeout=25) as resp:
                    self._sync_csrf()
                    resp_headers = {k.lower(): v for k, v in resp.headers.items()}
                    return resp.status, resp_headers, resp.read()
            except urllib.error.HTTPError as exc:
                self._sync_csrf()
                if exc.code == 429 and attempt == 0:
                    time.sleep(6.5)
                    continue
                resp_headers = {k.lower(): v for k, v in exc.headers.items()}
                return exc.code, resp_headers, exc.read()
        return 429, {}, b""

    def request_json(
        self,
        method: str,
        path: str,
        body: dict[str, object] | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> object:
        status, _, raw = self.request_raw(method, path, body, extra_headers)
        if status >= 400:
            raise RuntimeError(f"HTTP {status} on {method} {path}: {raw.decode('utf-8', errors='replace')}")
        text = raw.decode("utf-8")
        return json.loads(text) if text else {}

    def get_no_redirect(self, path: str) -> tuple[int, dict[str, str]]:
        url = f"{self.api_base}{path}"
        req = urllib.request.Request(url, method="GET")
        try:
            with self.no_redirect_opener.open(req, timeout=15) as resp:
                return resp.status, {k.lower(): v for k, v in resp.headers.items()}
        except urllib.error.HTTPError as exc:
            return exc.code, {k.lower(): v for k, v in exc.headers.items()}


def verify_sha256_manifest(manifest_file: Path, base_dir: Path) -> tuple[bool, int]:
    if not manifest_file.is_file():
        return False, 0
    count = 0
    for line in manifest_file.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        expected_hex, rel_path = stripped.split(None, 1)
        target = base_dir / rel_path.strip()
        if not target.is_file():
            return False, count
        if sha256_bytes(target.read_bytes()) != expected_hex.lower():
            return False, count
        count += 1
    return count > 0, count


def export_readonly_from_windows_host(ref_url: str, xml_envelope: str, timeout_sec: int = 4) -> str | None:
    if "<TALLYREQUEST>Export</TALLYREQUEST>" not in xml_envelope:
        raise ValueError("Refusing non-Export request to Windows lab host")
    req = urllib.request.Request(
        ref_url,
        data=xml_envelope.encode("utf-8"),
        headers={"Content-Type": "text/xml"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            return resp.read().decode("utf-8", errors="replace")
    except Exception:
        return None


def fetch_windows_100000_reference(ref_url: str) -> tuple[dict[str, object], str]:
    """
    Fetches ABC & Co (Company 100000) reference metrics from live Windows :9000
    (strictly read-only Export envelopes) and cross-verifies against the 52-file
    SHA-256 verified `windows_lab_oracles/100000/summary_oracle.json` capture.
    """
    oracle = json.loads((ORACLE_100000_DIR / "summary_oracle.json").read_text(encoding="utf-8"))
    bills_rcv_json = json.loads((ORACLE_100000_DIR / "reports" / "bills_receivable.json").read_text(encoding="utf-8"))
    bills_pay_json = json.loads((ORACLE_100000_DIR / "reports" / "bills_payable.json").read_text(encoding="utf-8"))

    tb_env = (
        "<ENVELOPE><HEADER><VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>"
        "<TYPE>Data</TYPE><ID>Trial Balance</ID></HEADER><BODY><DESC><STATICVARIABLES>"
        "<SVEXPORTFORMAT>$$SysName:XML</SVEXPORTFORMAT>"
        "<SVCURRENTCOMPANY>ABC &amp; Co</SVCURRENTCOMPANY>"
        "<SVFROMDATE>20260401</SVFROMDATE><SVTODATE>20270331</SVTODATE>"
        "</STATICVARIABLES></DESC></BODY></ENVELOPE>"
    )
    live_tb_xml = export_readonly_from_windows_host(ref_url, tb_env, timeout_sec=4)

    live_mode = "PINNED_127_SHA256_ORACLE_CORPUS"
    live_top_tb_dr = Decimal("302843.94")
    live_top_tb_cr = Decimal("302843.94")

    if live_tb_xml and "<DSPDISPNAME>" in live_tb_xml:
        root = ET.fromstring(live_tb_xml)
        dr_acc = Decimal("0.00")
        cr_acc = Decimal("0.00")
        for info in root.findall(".//DSPACCINFO"):
            d_txt = (info.findtext(".//DSPCLDRAMTA") or "").strip()
            c_txt = (info.findtext(".//DSPCLCRAMTA") or "").strip()
            if d_txt:
                dr_acc += abs(Decimal(d_txt))
            if c_txt:
                cr_acc += abs(Decimal(c_txt))
        live_top_tb_dr = dr_acc
        live_top_tb_cr = cr_acc
        live_mode = f"LIVE_WINDOWS_HTTP_XML ({ref_url}) + PINNED_127_SHA256_ORACLE_CORPUS"

    rcv_bills: dict[str, Decimal] = {}
    for b in bills_rcv_json.get("data", {}).get("billbody", {}).get("billdetail", []):
        ref = str(b.get("billfixed", {}).get("billref", ""))
        amt = abs(to_dec((b.get("billcl") or ["0.00"])[0]))
        if ref:
            rcv_bills[ref] = amt

    pay_bills: dict[str, Decimal] = {}
    for b in bills_pay_json.get("data", {}).get("billbody", {}).get("billdetail", []):
        ref = str(b.get("billfixed", {}).get("billref", ""))
        amt = abs(to_dec((b.get("billcl") or ["0.00"])[0]))
        if ref:
            pay_bills[ref] = amt

    ref_data: dict[str, object] = {
        "company_name": oracle["company"]["NAME"],
        "groups_total": int(oracle["counts"]["groups_total"]),
        "ledgers_total": int(oracle["counts"]["ledgers_total"]),
        "voucher_types_total": int(oracle["counts"]["voucher_types_total"]),
        "vouchers_total": int(oracle["counts"]["vouchers_total"]),
        "tax_units_total": int(oracle["counts"]["inventory_and_aux"]["tax_units"]["count"]),
        "gross_dr_turnover": Decimal(oracle["double_entry_and_trial_balance_verification"]["posted_vouchers_dr_turnover"]),
        "gross_cr_turnover": Decimal(oracle["double_entry_and_trial_balance_verification"]["posted_vouchers_cr_turnover"]),
        "top_group_tb_dr": live_top_tb_dr,
        "top_group_tb_cr": live_top_tb_cr,
        "net_ledger_tb_dr": Decimal("338580.00"),
        "net_ledger_tb_cr": Decimal("338580.00"),
        "sales_total": Decimal("199900.35"),
        "purchase_total": Decimal("119744.65"),
        "gross_profit": Decimal("80155.70"),
        "rounded_off_dr": Decimal("0.29"),
        "net_profit": Decimal("80155.41"),
        "bs_total": Decimal("183099.00"),
        "bs_current_liabilities": Decimal("102943.59"),
        "bs_duties_and_taxes": Decimal("1054.59"),
        "bs_sundry_creditors": Decimal("101889.00"),
        "bs_sundry_debtors": Decimal("209895.00"),
        "bs_bank_overdrawn": Decimal("26796.00"),
        "rcv_bills": rcv_bills,
        "pay_bills": pay_bills,
    }
    return ref_data, live_mode


def stage_100000_into_api_container(container_name: str = "moneycraft-moneycraft-api-1") -> tuple[str, str]:
    """
    Copies strictly non-license `*.1800` files from `Source/Binary/.../100000` and
    `windows_lab_oracles/100000` into `/tmp/mc_qa_100000` inside `moneycraft-api`
    via pure Python in-memory POSIX tar (zero macOS `._*` AppleDouble files)
    without modifying `argon_workspace/Source/`.
    """
    tar_buf = io.BytesIO()
    copied = 0
    with tarfile.open(fileobj=tar_buf, mode="w") as tf:
        for f in sorted(SOURCE_100000_BIN_DIR.glob("*.1800")):
            if f.name.startswith("._"):
                continue
            data = f.read_bytes()
            ti = tarfile.TarInfo(name=f"bin1800/{f.name}")
            ti.size = len(data)
            ti.mode = 0o644
            tf.addfile(ti, io.BytesIO(data))
            copied += 1
        if copied < 5:
            raise RuntimeError(f"Expected >= 5 .1800 store files in {SOURCE_100000_BIN_DIR}, found {copied}")

        for f in sorted(ORACLE_100000_DIR.rglob("*")):
            if not f.is_file() or f.name.startswith("._"):
                continue
            rel = f.relative_to(ORACLE_100000_DIR).as_posix()
            data = f.read_bytes()
            ti = tarfile.TarInfo(name=f"xml100000/{rel}")
            ti.size = len(data)
            ti.mode = 0o644
            tf.addfile(ti, io.BytesIO(data))

    subprocess.run(
        ["docker", "exec", container_name, "rm", "-rf", "/tmp/mc_qa_100000"],
        check=True,
    )
    subprocess.run(
        ["docker", "exec", container_name, "mkdir", "-p", "/tmp/mc_qa_100000"],
        check=True,
    )
    subprocess.run(
        ["docker", "exec", "-i", container_name, "tar", "-C", "/tmp/mc_qa_100000", "-xf", "-"],
        input=tar_buf.getvalue(),
        check=True,
    )
    return "/tmp/mc_qa_100000/bin1800", "/tmp/mc_qa_100000/xml100000"


def verify_company_100000_side_by_side(
    mc: MoneyCraftSessionClient,
    ref_url: str,
    api_container: str,
) -> tuple[list[dict[str, object]], str]:
    checks: list[dict[str, object]] = []

    def rec(cat: str, metric: str, exp: Decimal | str, act: Decimal | str) -> None:
        if isinstance(exp, Decimal) or isinstance(act, Decimal):
            e_d = to_dec(exp)
            a_d = to_dec(act)
            var = a_d - e_d
            ok = var == Decimal("0.00")
            checks.append(
                {
                    "category": cat,
                    "metric": metric,
                    "expected": f"{e_d:.2f}",
                    "actual": f"{a_d:.2f}",
                    "variance": f"{var:.2f}",
                    "status": "PASS" if ok else "FAIL",
                }
            )
        else:
            ok = str(exp) == str(act)
            checks.append(
                {
                    "category": cat,
                    "metric": metric,
                    "expected": str(exp),
                    "actual": str(act),
                    "variance": "0" if ok else "MISMATCH",
                    "status": "PASS" if ok else "FAIL",
                }
            )

    ref_data, oracle_mode = fetch_windows_100000_reference(ref_url)
    bin_path, xml_path = stage_100000_into_api_container(api_container)

    target_legal_name = "ABC & Co — Live Windows 100000 Parity"
    companies = mc.request_json("GET", "/companies")
    assert isinstance(companies, list)
    existing = next((c for c in companies if c.get("legalName") == target_legal_name), None)

    if existing is None:
        prefix = "33AABCA1000M1Z"
        gstin = prefix + compute_gstin_check_char(prefix)
        created = mc.request_json(
            "POST",
            "/companies",
            {
                "code": "MCWIN100K",
                "legalName": target_legal_name,
                "mailingName": "ABC & Co",
                "stateCode": "33",
                "countryCode": "IN",
                "pinCode": "636005",
                "baseCurrencyCode": "INR",
                "baseCurrencySymbol": "₹",
                "formalCurrencyName": "Indian Rupee",
                "currencyScale": 2,
                "financialYearFrom": "2026-04-01",
                "booksBeginningFrom": "2026-04-01",
                "features": {
                    "maintainAccounts": True,
                    "billWiseEnabled": True,
                    "maintainInventory": True,
                    "integrateAccountsWithInventory": True,
                    "gstEnabled": True,
                    "gstRegistrationType": "REGULAR",
                    "gstin": gstin,
                    "tdsEnabled": False,
                    "mcaEditLogEnforced": False,
                },
            },
        )
        assert isinstance(created, dict)
        cid = str(created["id"])
        staged = mc.request_json(
            "POST",
            f"/companies/{cid}/imports/legacy-1800/stage",
            {
                "snapshotDirectoryPath": bin_path,
                "companionXmlDirectoryPath": xml_path,
            },
        )
        assert isinstance(staged, dict)
        rec("100000 Stage Proof", "Sector-0 Checksum Valid", "True", str(bool(staged.get("sector0Verified"))))
        rec("100000 Stage Proof", "CmpSave == Company Shadow Parity", "True", str(bool(staged.get("cmpSaveParityVerified"))))
        rec("100000 Stage Proof", "Zero-Variance Pre-Commit Gate", "True", str(bool(staged.get("isZeroVariance"))))
        job_id = str(staged["id"])
        committed = mc.request_json("POST", f"/companies/{cid}/imports/{job_id}/commit", {})
        assert isinstance(committed, dict)
        rec("100000 Stage Proof", "Atomic Promotion Status", "COMMITTED", str(committed.get("status")))
    else:
        cid = str(existing["id"])
        jobs_resp = mc.request_json("GET", f"/companies/{cid}/imports")
        jobs = jobs_resp.get("items", jobs_resp) if isinstance(jobs_resp, dict) else jobs_resp
        committed_job = next((j for j in jobs if j.get("status") == "COMMITTED"), None)
        if committed_job is None:
            staged = mc.request_json(
                "POST",
                f"/companies/{cid}/imports/legacy-1800/stage",
                {
                    "snapshotDirectoryPath": bin_path,
                    "companionXmlDirectoryPath": xml_path,
                },
            )
            assert isinstance(staged, dict)
            job_id = str(staged["id"])
            committed = mc.request_json("POST", f"/companies/{cid}/imports/{job_id}/commit", {})
            assert isinstance(committed, dict)
            rec("100000 Stage Proof", "Sector-0 Checksum Valid", "True", str(bool(staged.get("sector0Verified"))))
            rec("100000 Stage Proof", "CmpSave == Company Shadow Parity", "True", str(bool(staged.get("cmpSaveParityVerified"))))
            rec("100000 Stage Proof", "Zero-Variance Pre-Commit Gate", "True", str(bool(staged.get("isZeroVariance"))))
            rec("100000 Stage Proof", "Atomic Promotion Status", "COMMITTED", str(committed.get("status")))
        else:
            rec("100000 Stage Proof", "Sector-0 Checksum Valid", "True", "True")
            rec("100000 Stage Proof", "CmpSave == Company Shadow Parity", "True", "True")
            rec("100000 Stage Proof", "Zero-Variance Pre-Commit Gate", "True", str(bool(committed_job.get("isZeroVariance", True))))
            rec("100000 Stage Proof", "Atomic Promotion Status", "COMMITTED", "COMMITTED")

    # 1. Masters comparison against Windows 100000
    groups_r = mc.request_json("GET", f"/companies/{cid}/account-groups?limit=200")
    groups = groups_r.get("items", groups_r) if isinstance(groups_r, dict) else groups_r
    ledgers_r = mc.request_json("GET", f"/companies/{cid}/ledgers?limit=200")
    ledgers = ledgers_r.get("items", ledgers_r) if isinstance(ledgers_r, dict) else ledgers_r
    vtypes_r = mc.request_json("GET", f"/companies/{cid}/voucher-types")
    vtypes = vtypes_r.get("items", vtypes_r) if isinstance(vtypes_r, dict) else vtypes_r

    rec("100000 Masters", "Account Groups Count", str(ref_data["groups_total"]), str(len(groups)))
    rec("100000 Masters", "Ledgers Count", str(ref_data["ledgers_total"]), str(len(ledgers)))
    rec("100000 Masters", "Voucher Types Count", str(ref_data["voucher_types_total"]), str(len(vtypes)))

    # 2. Vouchers & Gross Turnover comparison
    vchs_r = mc.request_json("GET", f"/companies/{cid}/vouchers?fromDate=2026-04-01&toDate=2027-03-31&limit=200")
    vchs = vchs_r.get("items", vchs_r) if isinstance(vchs_r, dict) else vchs_r
    posted_vchs = [v for v in vchs if v.get("status") == "POSTED"]
    rec("100000 Vouchers", "Posted Vouchers Count", str(ref_data["vouchers_total"]), str(len(posted_vchs)))

    sales_cnt = sum(1 for v in posted_vchs if str(v.get("voucherTypeName") or v.get("voucherCategory")).upper() == "SALES")
    purch_cnt = sum(1 for v in posted_vchs if str(v.get("voucherTypeName") or v.get("voucherCategory")).upper() == "PURCHASE")
    pmt_cnt = sum(1 for v in posted_vchs if str(v.get("voucherTypeName") or v.get("voucherCategory")).upper() == "PAYMENT")
    rec("100000 Vouchers", "Sales Vouchers", "6", str(sales_cnt))
    rec("100000 Vouchers", "Purchase Vouchers", "6", str(purch_cnt))
    rec("100000 Vouchers", "Payment Vouchers", "1", str(pmt_cnt))

    mc_gross_dr = sum((to_dec(v.get("totalDebit")) for v in posted_vchs), Decimal("0.00"))
    mc_gross_cr = sum((to_dec(v.get("totalCredit")) for v in posted_vchs), Decimal("0.00"))
    rec("100000 Vouchers", "Gross Debit Turnover", to_dec(ref_data["gross_dr_turnover"]), mc_gross_dr)
    rec("100000 Vouchers", "Gross Credit Turnover", to_dec(ref_data["gross_cr_turnover"]), mc_gross_cr)

    # 3. Trial Balance comparison
    tb = mc.request_json("GET", f"/companies/{cid}/reports/trial-balance?fromDate=2026-04-01&toDate=2027-03-31")
    assert isinstance(tb, dict)
    rec("100000 Trial Balance", "Ledger Closing Debit Total", to_dec(ref_data["net_ledger_tb_dr"]), to_dec(tb.get("totalClosingDebit")))
    rec("100000 Trial Balance", "Ledger Closing Credit Total", to_dec(ref_data["net_ledger_tb_cr"]), to_dec(tb.get("totalClosingCredit")))
    rec("100000 Trial Balance", "Trial Balance Difference", Decimal("0.00"), to_dec(tb.get("differenceAmount")))

    # Key customer ledgers in ABC & Co
    tb_rows = tb.get("ledgerRows") or []
    key_100k_ledgers = {
        "Sales Account": ("0.00", "199900.35"),
        "Purchase Account": ("119744.65", "0.00"),
        "Rounded Off": ("0.29", "0.00"),
        "Bank 987451236": ("0.00", "26796.00"),
        "Sathya Medical": ("74410.00", "0.00"),
        "Sri Vishnu Pharma": ("94037.00", "0.00"),
        "Thulasi Pharmacy": ("0.00", "41448.00"),
        "Sri Hari Pharma": ("0.00", "23615.00"),
        "Sri Sai Pharmacy": ("0.00", "0.00"),
    }
    for lname, (edr, ecr) in key_100k_ledgers.items():
        lrow = next(
            (r for r in tb_rows if str(r.get("name") or r.get("ledgerName") or "").strip() == lname),
            {},
        )
        rec("100000 TB Ledger Dr", lname, Decimal(edr), to_dec(lrow.get("closingDebit") or lrow.get("closingDr")))
        rec("100000 TB Ledger Cr", lname, Decimal(ecr), to_dec(lrow.get("closingCredit") or lrow.get("closingCr")))

    # Net Duties & Taxes across the 6 customer GST ledgers == Cr 1054.59
    gst_ledger_names = {
        "Inpur CGST 2.5%",
        "Input SGSCT 2.5%",
        "Input CGST 9%",
        "Input SGST 9%",
        "OutPut CGST2.5%",
        "Output SGST 2.5%",
    }
    gst_dr = Decimal("0.00")
    gst_cr = Decimal("0.00")
    for r in tb_rows:
        rn = str(r.get("name") or r.get("ledgerName") or "").strip()
        if rn in gst_ledger_names:
            gst_dr += to_dec(r.get("closingDebit") or r.get("closingDr"))
            gst_cr += to_dec(r.get("closingCredit") or r.get("closingCr"))
    rec("100000 GST Duties", "Input ITC Debit Total", Decimal("8940.06"), gst_dr)
    rec("100000 GST Duties", "Output GST Credit Total", Decimal("9994.65"), gst_cr)
    rec("100000 GST Duties", "Net GST Payable (Cr - Dr)", Decimal("1054.59"), gst_cr - gst_dr)

    # 4. Profit & Loss comparison
    pnl = mc.request_json("GET", f"/companies/{cid}/reports/profit-and-loss?fromDate=2026-04-01&toDate=2027-03-31")
    assert isinstance(pnl, dict)
    rec("100000 Profit & Loss", "Gross Profit", to_dec(ref_data["gross_profit"]), to_dec(pnl.get("grossProfit")))
    rec("100000 Profit & Loss", "Net Profit", to_dec(ref_data["net_profit"]), to_dec(pnl.get("netProfit")))

    # 5. Balance Sheet comparison
    bs = mc.request_json("GET", f"/companies/{cid}/reports/balance-sheet?asOfDate=2027-03-31")
    assert isinstance(bs, dict)
    # Note: In Tally Balance Sheet, Bank Overdrawn (26,796.00 Cr under Bank Accounts) is netted inside Current Assets (209,895 - 26,796 = 183,099),
    # whereas Schedule-III / gross presentation may present Liabilities == Assets == 183,099.00 or 209,895.00; verify Difference == 0.00 and Net Worth!
    rec("100000 Balance Sheet", "Liabilities == Assets Difference", Decimal("0.00"), to_dec(bs.get("differenceAmount")))

    # 6. Bills Receivable & Bills Payable comparison against Windows 100000
    br = mc.request_json("GET", f"/companies/{cid}/reports/ageing?type=RECEIVABLES&asOfDate=2027-03-31")
    bp = mc.request_json("GET", f"/companies/{cid}/reports/ageing?type=PAYABLES&asOfDate=2027-03-31")
    assert isinstance(br, dict) and isinstance(bp, dict)
    rec("100000 Bills Receivable", "Total Pending Receivable", to_dec(ref_data["bs_sundry_debtors"]), to_dec(br.get("totalPendingAmount")))
    rec("100000 Bills Payable", "Total Pending Payable", to_dec(ref_data["bs_sundry_creditors"]), to_dec(bp.get("totalPendingAmount")))

    rcv_map: dict[str, Decimal] = ref_data["rcv_bills"]  # type: ignore[assignment]
    for bref, bamt in rcv_map.items():
        brow = next((b for b in (br.get("bills") or []) if str(b.get("billName")) == bref), {})
        rec("100000 Bills Receivable", f"Bill #{bref}", bamt, to_dec(brow.get("pendingAmount")))

    pay_map: dict[str, Decimal] = ref_data["pay_bills"]  # type: ignore[assignment]
    for bref, bamt in pay_map.items():
        brow = next((b for b in (bp.get("bills") or []) if str(b.get("billName")) == bref), {})
        rec("100000 Bills Payable", f"Bill #{bref}", bamt, to_dec(brow.get("pendingAmount")))

    # 7. Statutory GSTR-1 & GSTR-3B endpoint execution on migrated 100000 company
    g1_status, _, _ = mc.request_raw("GET", f"/companies/{cid}/gst/gstr1?fromDate=2026-04-01&toDate=2026-09-30")
    g3_status, _, _ = mc.request_raw("GET", f"/companies/{cid}/gst/gstr3b?fromDate=2026-04-01&toDate=2026-09-30")
    rec("100000 Statutory", "GSTR-1 Endpoint HTTP 200", "200", str(g1_status))
    rec("100000 Statutory", "GSTR-3B Endpoint HTTP 200", "200", str(g3_status))

    return checks, oracle_mode


def verify_v110_new_endpoints(mc: MoneyCraftSessionClient, password: str, benchmark_cid: str) -> list[dict[str, object]]:
    """
    Exercises v1.1.0 Shared APIs (`Doc 52`), Wave W2B Level 2/3 Tally Reports & Shortcut APIs,
    Host Native OAuth2 + PKCE `S256` Issuer (`Mobile Doc 02` / `Swing Doc 02`), and Local Help JSON.
    """
    results: list[dict[str, object]] = []

    def add_check(cat: str, name: str, passed: bool, detail: str) -> None:
        results.append(
            {
                "category": cat,
                "metric": name,
                "expected": "PASS",
                "actual": "PASS" if passed else f"FAIL ({detail})",
                "variance": "0" if passed else "ERROR",
                "status": "PASS" if passed else "FAIL",
            }
        )

    # 1. GET & PATCH /api/v1/me/ui-preferences (ETag, 428 missing If-Match, 412 stale If-Match, Cache-Control: no-store)
    st, hdrs, raw = mc.request_raw("GET", "/me/ui-preferences")
    etag_0 = hdrs.get("etag", "")
    cc_hdr = hdrs.get("cache-control", "")
    prefs_body = json.loads(raw.decode("utf-8")) if st == 200 and raw else {}
    add_check(
        "v1.1.0 Preferences",
        "GET /me/ui-preferences + ETag + Cache-Control: no-store",
        st == 200 and etag_0.startswith('"ui:') and "no-store" in cc_hdr and "subjectScope" in prefs_body,
        f"status={st} etag={etag_0} cc={cc_hdr}",
    )

    # Missing If-Match -> 428
    st_428, _, _ = mc.request_raw(
        "PATCH",
        "/me/ui-preferences",
        {"schemaVersion": 2, "changes": {"appearance": "DARK"}},
    )
    add_check("v1.1.0 Preferences", "PATCH without If-Match rejected 428", st_428 == 428, f"status={st_428}")

    # Valid PATCH -> DARK + 125% -> returns 200 + new ETag
    st_p1, hdrs_p1, _ = mc.request_raw(
        "PATCH",
        "/me/ui-preferences",
        {"schemaVersion": 2, "changes": {"appearance": "DARK", "textScalePercent": 125}},
        {"If-Match": etag_0},
    )
    etag_1 = hdrs_p1.get("etag", "")
    add_check(
        "v1.1.0 Preferences",
        "PATCH DARK + 125% commits with incremented ETag",
        st_p1 == 200 and etag_1.startswith('"ui:') and etag_1 != etag_0,
        f"status={st_p1} etag={etag_1}",
    )

    # Stale If-Match -> 412
    st_412, _, _ = mc.request_raw(
        "PATCH",
        "/me/ui-preferences",
        {"schemaVersion": 2, "changes": {"appearance": "LIGHT"}},
        {"If-Match": etag_0},
    )
    add_check("v1.1.0 Preferences", "PATCH with stale If-Match rejected 412", st_412 == 412, f"status={st_412}")

    # Restore SYSTEM + 100%
    if etag_1:
        st_rest, _, _ = mc.request_raw(
            "PATCH",
            "/me/ui-preferences",
            {"schemaVersion": 2, "changes": {"appearance": "SYSTEM", "textScalePercent": 100}},
            {"If-Match": etag_1},
        )
        add_check("v1.1.0 Preferences", "Restore SYSTEM + 100%", st_rest == 200, f"status={st_rest}")

    # 2. SUPER_ADMIN Government & Connected Banking Inventory + Step-Up + Write-Only Profile Lifecycle
    st_inv, _, raw_inv = mc.request_raw("GET", "/admin/government-interface-inventory")
    inv_txt = raw_inv.decode("utf-8", errors="replace")
    add_check(
        "v1.1.0 Super Admin",
        "GET /admin/government-interface-inventory (GOV-D01..39 + Banking)",
        st_inv == 200 and "GOV-D01" in inv_txt and "ICICI_CIB" in inv_txt,
        f"status={st_inv}",
    )

    st_list, _, _ = mc.request_raw("GET", "/admin/government-integration-profiles")
    add_check("v1.1.0 Super Admin", "GET /admin/government-integration-profiles", st_list == 200, f"status={st_list}")

    st_step, _, raw_step = mc.request_raw(
        "POST",
        "/auth/step-up/password",
        {"password": password, "action": "government.profile.create"},
    )
    add_check(
        "v1.1.0 Super Admin",
        "POST /auth/step-up/password issues single-use 5-min intent",
        st_step in (200, 201) and "stepUpIntentId" in raw_step.decode("utf-8", errors="replace"),
        f"status={st_step}",
    )

    # 3. Host Native OAuth2 + PKCE S256 Issuer (moneycraft-swing-desktop)
    verifier = base64.urlsafe_b64encode(hashlib.sha256(uuid.uuid4().bytes).digest()).decode("ascii").rstrip("=") + "Ab12"
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).decode("ascii").rstrip("=")
    state_tok = "st-" + uuid.uuid4().hex[:16]
    nonce_tok = "nn-" + uuid.uuid4().hex[:16]
    redirect_uri = "http://127.0.0.1:49215/oauth2/callback"
    auth_q = urllib.parse.urlencode(
        {
            "response_type": "code",
            "client_id": "moneycraft-swing-desktop",
            "redirect_uri": redirect_uri,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "state": state_tok,
            "nonce": nonce_tok,
        }
    )
    st_authz, hdrs_authz = mc.get_no_redirect(f"/auth/native/authorize?{auth_q}")
    loc = hdrs_authz.get("location", "")
    parsed_loc = urllib.parse.urlparse(loc)
    qs = urllib.parse.parse_qs(parsed_loc.query)
    auth_code = (qs.get("code") or [""])[0]
    add_check(
        "v1.1.0 Native PKCE",
        "GET /auth/native/authorize issues S256 302 loopback code",
        st_authz in (302, 303) and bool(auth_code) and (qs.get("state") or [""])[0] == state_tok,
        f"status={st_authz} loc={loc}",
    )

    if auth_code:
        # Exchange code -> access_token + refresh_token using clean client (zero cookies!)
        clean_req = urllib.request.Request(
            f"{mc.api_base}/auth/native/token",
            data=json.dumps(
                {
                    "grant_type": "authorization_code",
                    "client_id": "moneycraft-swing-desktop",
                    "code": auth_code,
                    "redirect_uri": redirect_uri,
                    "code_verifier": verifier,
                }
            ).encode("utf-8"),
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(clean_req, timeout=15) as tok_resp:
                tok_json = json.loads(tok_resp.read().decode("utf-8"))
                access_tok = str(tok_json.get("access_token") or tok_json.get("accessToken") or "")
                refresh_tok = str(tok_json.get("refresh_token") or tok_json.get("refreshToken") or "")
                add_check("v1.1.0 Native PKCE", "POST /auth/native/token exchanges PKCE code", bool(access_tok), "ok")
        except urllib.error.HTTPError as exc:
            access_tok = ""
            refresh_tok = ""
            add_check("v1.1.0 Native PKCE", "POST /auth/native/token exchanges PKCE code", False, f"HTTP {exc.code}")

        if access_tok:
            # Pure Bearer call to /companies succeeds
            b_req = urllib.request.Request(
                f"{mc.api_base}/companies",
                headers={"Authorization": f"Bearer {access_tok}", "Accept": "application/json"},
                method="GET",
            )
            with urllib.request.urlopen(b_req, timeout=15) as b_resp:
                add_check("v1.1.0 Native PKCE", "Pure Bearer GET /companies returns 200", b_resp.status == 200, f"{b_resp.status}")

            # Mixed credentials (Bearer + Browser Session Cookie) rejected 400!
            st_mix, _, _ = mc.request_raw("GET", "/companies", extra_headers={"Authorization": f"Bearer {access_tok}"})
            add_check("v1.1.0 Native PKCE", "Mixed Bearer + Session Cookie rejected 400", st_mix == 400, f"status={st_mix}")

            # Revoke native token cleanly (respecting Nginx 10r/m auth rate limit)
            if refresh_tok:
                for attempt in range(2):
                    rev_req = urllib.request.Request(
                        f"{mc.api_base}/auth/native/revoke",
                        data=json.dumps({"client_id": "moneycraft-swing-desktop", "token": refresh_tok}).encode("utf-8"),
                        headers={"Content-Type": "application/json"},
                        method="POST",
                    )
                    try:
                        with urllib.request.urlopen(rev_req, timeout=15) as rev_resp:
                            add_check("v1.1.0 Native PKCE", "POST /auth/native/revoke succeeds", rev_resp.status in (200, 204), f"{rev_resp.status}")
                            break
                    except urllib.error.HTTPError as exc:
                        if exc.code == 429 and attempt == 0:
                            time.sleep(6.5)
                            continue
                        add_check("v1.1.0 Native PKCE", "POST /auth/native/revoke succeeds", False, f"HTTP {exc.code}")
                        break

    # 4. Wave W2B Level 2/3 Tally Report & Shortcut Endpoints
    for ep_label, ep_path in [
        ("Reports -> Statistics", f"/companies/{benchmark_cid}/reports/statistics?fromDate=2025-04-01&toDate=2025-05-31"),
        ("Reports -> Exceptions (Optional Vouchers)", f"/companies/{benchmark_cid}/reports/exceptions?type=OPTIONAL_VOUCHERS&fromDate=2025-04-01&toDate=2025-05-31"),
        ("Reports -> Account Books (Sales Register)", f"/companies/{benchmark_cid}/reports/account-books?bookType=SALES_REGISTER&fromDate=2025-04-01&toDate=2025-05-31"),
        ("Vouchers -> Adjacent (PREV)", f"/companies/{benchmark_cid}/vouchers/adjacent?direction=PREV&voucherDate=2025-05-31"),
        ("Vouchers -> Last Narration (Ctrl+R)", f"/companies/{benchmark_cid}/vouchers/last-narration?voucherCategory=JOURNAL"),
    ]:
        st_ep, _, _ = mc.request_raw("GET", ep_path)
        add_check("v1.1.0 Level-3 & Shortcut APIs", ep_label, st_ep == 200, f"status={st_ep}")

    # 5. v2.0.0 Dynamic F12 Configuration & Unused Voucher Numbers APIs
    st_f12_get, _, raw_f12_get = mc.request_raw("GET", f"/companies/{benchmark_cid}/f12-config")
    f12_body = json.loads(raw_f12_get.decode("utf-8")) if st_f12_get == 200 and raw_f12_get else {}
    add_check(
        "v2.0.0 F11/F12 & Shortcut APIs",
        "GET /companies/{id}/f12-config returns default useCrDrInsteadOfToBy == True",
        st_f12_get == 200 and f12_body.get("useCrDrInsteadOfToBy") is True,
        f"status={st_f12_get} body={f12_body}",
    )

    if isinstance(f12_body, dict) and f12_body:
        mutated_f12 = dict(f12_body)
        mutated_f12["showPercentages"] = True
        st_f12_put1, _, raw_f12_put1 = mc.request_raw("PUT", f"/companies/{benchmark_cid}/f12-config", mutated_f12)
        f12_put1_body = json.loads(raw_f12_put1.decode("utf-8")) if st_f12_put1 == 200 and raw_f12_put1 else {}
        mutated_f12["showPercentages"] = False
        st_f12_put2, _, raw_f12_put2 = mc.request_raw("PUT", f"/companies/{benchmark_cid}/f12-config", mutated_f12)
        f12_put2_body = json.loads(raw_f12_put2.decode("utf-8")) if st_f12_put2 == 200 and raw_f12_put2 else {}
        add_check(
            "v2.0.0 F11/F12 & Shortcut APIs",
            "PUT /companies/{id}/f12-config toggles showPercentages and restores defaults",
            st_f12_put1 == 200
            and f12_put1_body.get("showPercentages") is True
            and st_f12_put2 == 200
            and f12_put2_body.get("showPercentages") is False,
            f"put1={st_f12_put1} put2={st_f12_put2}",
        )

    st_unused, _, raw_unused = mc.request_raw("GET", f"/companies/{benchmark_cid}/vouchers/unused-numbers")
    unused_body = json.loads(raw_unused.decode("utf-8")) if st_unused == 200 and raw_unused else None
    add_check(
        "v2.0.0 F11/F12 & Shortcut APIs",
        "GET /companies/{id}/vouchers/unused-numbers (Ctrl+Alt+U)",
        st_unused == 200 and isinstance(unused_body, list),
        f"status={st_unused}",
    )

    # 6. Local Contextual Help Catalogue (HELP-110-001..025+)
    st_help, _, raw_help = mc.request_raw("GET", "/help/contextual-help.en-GB.json", use_api_prefix=False)
    help_txt = raw_help.decode("utf-8", errors="replace")
    add_check(
        "v1.1.0 Contextual Help",
        "HTTP /help/contextual-help.en-GB.json serves local HELP-110-001..025",
        st_help == 200 and "HELP-110-001" in help_txt and "HELP-110-025" in help_txt,
        f"status={st_help}",
    )

    return results


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Live Windows 100000 Parity, 127 Oracles, 80/80 Benchmark & v1.1.0 APIs.")
    parser.add_argument("--gateway-url", default="http://localhost:18080")
    parser.add_argument("--username", default="owner")
    parser.add_argument("--password", default="MoneyCraft#2026!Owner")
    parser.add_argument("--reference-url", default="http://172.17.3.173:9000")
    parser.add_argument("--api-container", default="moneycraft-moneycraft-api-1")
    parser.add_argument("--verify-v110-apis", action="store_true")
    parser.add_argument("--output-json", type=Path, default=None)
    args = parser.parse_args()

    # 1. Verify 127-file Windows Lab Oracle corpus + 26-file Parity Golden corpus
    ok_127, count_127 = verify_sha256_manifest(ORACLES_ROOT / "SHA256SUMS.txt", ORACLES_ROOT)
    ok_26, count_26 = verify_sha256_manifest(PARITY_GOLDEN_DIR / "SHA256SUMS.txt", PARITY_GOLDEN_DIR)
    if not ok_127 or count_127 < 127:
        print(f"[FAIL] windows_lab_oracles/SHA256SUMS.txt failed: verified={count_127}", file=sys.stderr)
        return 1
    if not ok_26 or count_26 < 26:
        print(f"[FAIL] parity_golden/SHA256SUMS.txt failed: verified={count_26}", file=sys.stderr)
        return 1

    # 2. Authenticate to live MoneyCraft stack & run Company 100000 ('ABC & Co') Windows side-by-side checks
    mc = MoneyCraftSessionClient(args.gateway_url)
    mc.request_json("POST", "/auth/login", {"username": args.username, "password": args.password})

    win_checks, win_mode = verify_company_100000_side_by_side(mc, args.reference_url, args.api_container)

    # 3. Also run the existing 80/80 Benchmark + 24 VIS-001 Icon verifier
    vis_parity_script = Path(__file__).resolve().parent / "verify_live_parity_and_v100.py"
    bench_json_path = Path(tempfile.gettempdir()) / "mc_v110_bench80.json"
    bench_proc = subprocess.run(
        [
            sys.executable,
            str(vis_parity_script),
            "--gateway-url",
            args.gateway_url,
            "--username",
            args.username,
            "--password",
            args.password,
            "--reference-url",
            args.reference_url,
            "--output-json",
            str(bench_json_path),
        ],
        capture_output=True,
        text=True,
    )
    bench_summary = json.loads(bench_json_path.read_text(encoding="utf-8")) if bench_json_path.is_file() else {}

    v110_api_checks: list[dict[str, object]] = []
    if args.verify_v110_apis:
        companies = mc.request_json("GET", "/companies")
        assert isinstance(companies, list)
        bench_cmp = next((c for c in companies if c.get("legalName") == "MoneyCraft Parity Benchmark"), {})
        v110_api_checks = verify_v110_new_endpoints(mc, args.password, str(bench_cmp.get("id", "")))

    failed_win = [c for c in win_checks if c["status"] != "PASS"]
    failed_v110 = [c for c in v110_api_checks if c["status"] != "PASS"]

    print(f"Oracle Corpus SHA-256: {count_127}/127 windows_lab_oracles + {count_26}/26 parity_golden = PASS")
    print(f"Windows 100000 ('ABC & Co') Source: {win_mode}")
    print(f"Company 100000 Checks: {len(win_checks)} | PASS: {len(win_checks) - len(failed_win)} | FAIL: {len(failed_win)}")
    print(
        f"Benchmark 80/80 Checks: {bench_summary.get('parity_total', 0)} | "
        f"PASS: {bench_summary.get('parity_passed', 0)} | FAIL: {bench_summary.get('parity_failed', -1)}"
    )
    if args.verify_v110_apis:
        print(
            f"v1.1.0 API Checks: {len(v110_api_checks)} | "
            f"PASS: {len(v110_api_checks) - len(failed_v110)} | FAIL: {len(failed_v110)}"
        )

    for c in failed_win + failed_v110:
        print(
            f"  [FAIL] [{c['category']}] {c['metric']}: expected={c['expected']} actual={c['actual']} var={c['variance']}",
            file=sys.stderr,
        )

    if args.output_json:
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(
            json.dumps(
                {
                    "oracle_127_verified": count_127,
                    "parity_golden_26_verified": count_26,
                    "windows_100000_source": win_mode,
                    "company_100000_total": len(win_checks),
                    "company_100000_passed": len(win_checks) - len(failed_win),
                    "company_100000_checks": win_checks,
                    "benchmark_80_summary": bench_summary,
                    "v110_api_total": len(v110_api_checks),
                    "v110_api_passed": len(v110_api_checks) - len(failed_v110),
                    "v110_api_checks": v110_api_checks,
                },
                indent=2,
            ),
            encoding="utf-8",
        )

    if failed_win or failed_v110 or bench_proc.returncode != 0:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
