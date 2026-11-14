# syntax=docker/dockerfile:1.7
# ==============================================================================
# MoneyCraft 1.0.0 — Synchronized Dual-PG18 Backup & Encrypted WAL PITR Sidecar
# Runs strictly as non-root `postgres` (`uid 999:999`) under `supercronic`
# ==============================================================================

FROM postgres:18.6-bookworm@sha256:f1ea2a1a4fb7b68d4836be1fdcb8a6466a89b072158bf9d97663600538c294c5

ARG MC_VERSION=1.0.0-SNAPSHOT
ARG MC_VCS_REF=dev

LABEL org.opencontainers.image.title="moneycraft-pg-backup" \
      org.opencontainers.image.description="MoneyCraft 1.0.0 Dual-PG18 Causal-Cut Backup & AES-256-GCM WAL Archiver" \
      org.opencontainers.image.version="${MC_VERSION}" \
      org.opencontainers.image.revision="${MC_VCS_REF}"

USER root

# Install Python 3 + `cryptography` (streaming HKDF-SHA256 + AES-256-GCM matching Tink 1MB chunks),
# `zstd`, `jq`, `curl`, and `tini` cleanly from Debian Bookworm packages (multi-arch arm64/amd64 safe).
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        jq \
        openssl \
        python3 \
        python3-cryptography \
        tini \
        zstd && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /opt/moneycraft/backup /backups /wal-archive && \
    chown -R 999:999 /opt/moneycraft/backup /backups /wal-archive

# Deterministic non-root cron scheduler + streaming AES-256-GCM HKDF-1MB encryptor/decryptor
RUN cat > /opt/moneycraft/backup/mc_aead_stream.py <<'PY' && \
    chmod 0755 /opt/moneycraft/backup/mc_aead_stream.py
#!/usr/bin/env python3
"""Streaming AES-256-GCM-HKDF-1MB authenticated encryptor/decryptor (Tink wire-aligned).

Frame format:
  - Magic: 8 bytes b"MCTINK01"
  - Salt:  32 bytes random HKDF-SHA256 salt
  - Nonce prefix: 7 bytes random prefix
  - Chunks (1 MiB plaintext each):
      [uint32_be ciphertext_len][ciphertext + 16-byte GCM tag]
      where 12-byte nonce = 7-byte prefix || uint32_be(chunk_index) || last_block_byte(0x00|0x01)
"""
from __future__ import annotations

import os
import struct
import sys
from pathlib import Path
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

MAGIC = b"MCTINK01"
CHUNK_SIZE = 1024 * 1024


def load_key_material(key_file: Path) -> bytes:
    raw = key_file.read_bytes().strip()
    if len(raw) < 32:
        raise ValueError(f"Backup KEK at {key_file} must contain at least 32 bytes of key material.")
    return raw


def derive_subkey(ikm: bytes, salt: bytes, aad: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=aad).derive(ikm)


def encrypt_stream(in_path: Path, out_path: Path, key_file: Path, aad: bytes) -> None:
    ikm = load_key_material(key_file)
    salt = os.urandom(32)
    nonce_prefix = os.urandom(7)
    aead = AESGCM(derive_subkey(ikm, salt, aad))

    with in_path.open("rb") as fin, out_path.open("wb") as fout:
        fout.write(MAGIC)
        fout.write(salt)
        fout.write(nonce_prefix)
        chunk_idx = 0
        pending = fin.read(CHUNK_SIZE)
        while True:
            nxt = fin.read(CHUNK_SIZE)
            is_last = 1 if not nxt else 0
            nonce = nonce_prefix + struct.pack(">IB", chunk_idx, is_last)
            ct = aead.encrypt(nonce, pending, aad)
            fout.write(struct.pack(">I", len(ct)))
            fout.write(ct)
            if is_last:
                break
            pending = nxt
            chunk_idx += 1


def decrypt_stream(in_path: Path, out_path: Path, key_file: Path, aad: bytes) -> None:
    ikm = load_key_material(key_file)
    with in_path.open("rb") as fin, out_path.open("wb") as fout:
        magic = fin.read(8)
        if magic != MAGIC:
            raise ValueError("Invalid encrypted backup header magic.")
        salt = fin.read(32)
        nonce_prefix = fin.read(7)
        aead = AESGCM(derive_subkey(ikm, salt, aad))
        chunk_idx = 0
        saw_final = False
        while True:
            hdr = fin.read(4)
            if not hdr:
                break
            (ct_len,) = struct.unpack(">I", hdr)
            ct = fin.read(ct_len)
            if len(ct) != ct_len:
                raise ValueError("Truncated encrypted backup chunk.")
            peek = fin.read(1)
            if peek:
                fin.seek(-1, os.SEEK_CUR)
                is_last = 0
            else:
                is_last = 1
                saw_final = True
            nonce = nonce_prefix + struct.pack(">IB", chunk_idx, is_last)
            pt = aead.decrypt(nonce, ct, aad)
            fout.write(pt)
            chunk_idx += 1
        if not saw_final:
            raise ValueError("Encrypted stream ended without terminal authenticated chunk.")


def main() -> int:
    mode, src, dst, key_path, aad_str = sys.argv[1:6]
    aad = aad_str.encode("utf-8")
    if mode == "encrypt":
        encrypt_stream(Path(src), Path(dst), Path(key_path), aad)
    elif mode == "decrypt":
        decrypt_stream(Path(src), Path(dst), Path(key_path), aad)
    else:
        raise ValueError(f"Unknown mode: {mode}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
PY

RUN cat > /opt/moneycraft/backup/scheduler.py <<'PY' && \
    chmod 0755 /opt/moneycraft/backup/scheduler.py
#!/usr/bin/env python3
"""Lightweight non-root cron loop reading `/opt/moneycraft/backup/crontab` without setuid root."""
from __future__ import annotations

import signal
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

STOP = False


def handle_sig(_signum: int, _frame: object) -> None:
    global STOP
    STOP = True


def matches_field(expr: str, val: int) -> bool:
    if expr == "*":
        return True
    if expr.startswith("*/"):
        return val % int(expr[2:]) == 0
    return val in {int(part) for part in expr.split(",")}


def main() -> int:
    signal.signal(signal.SIGTERM, handle_sig)
    signal.signal(signal.SIGINT, handle_sig)
    crontab_path = Path(sys.argv[1] if len(sys.argv) > 1 else "/opt/moneycraft/backup/crontab")
    heartbeat = Path("/tmp/backup-sidecar-healthy")
    heartbeat.write_text("READY\n", encoding="utf-8")
    last_minute: tuple[int, int, int, int, int] | None = None

    while not STOP:
        now = datetime.now(timezone.utc)
        key = (now.year, now.month, now.day, now.hour, now.minute)
        heartbeat.write_text(now.isoformat() + "\n", encoding="utf-8")
        if key != last_minute:
            last_minute = key
            for raw in crontab_path.read_text(encoding="utf-8").splitlines():
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split(None, 5)
                if len(parts) < 6:
                    continue
                m_expr, h_expr, dom_expr, mon_expr, dow_expr, cmd = parts
                cron_dow = (now.weekday() + 1) % 7
                if (
                    matches_field(m_expr, now.minute)
                    and matches_field(h_expr, now.hour)
                    and matches_field(dom_expr, now.day)
                    and matches_field(mon_expr, now.month)
                    and matches_field(dow_expr, cron_dow)
                ):
                    print(f"[scheduler] {now.isoformat()} -> {cmd}", flush=True)
                    subprocess.run(["/bin/bash", "-lc", cmd], check=False)
        time.sleep(5)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
PY

COPY --chown=999:999 crontab /opt/moneycraft/backup/crontab
COPY --chown=999:999 backup-and-wal-archive.sh /opt/moneycraft/backup/backup-and-wal-archive.sh
COPY --chown=999:999 restore-pitr.sh /opt/moneycraft/backup/restore-pitr.sh

RUN chmod 0755 /opt/moneycraft/backup/backup-and-wal-archive.sh /opt/moneycraft/backup/restore-pitr.sh && \
    chmod 0644 /opt/moneycraft/backup/crontab

USER 999:999
WORKDIR /opt/moneycraft/backup

HEALTHCHECK --interval=20s --timeout=5s --start-period=10s --retries=3 \
    CMD test -f /tmp/backup-sidecar-healthy && test -w /backups && test -w /wal-archive

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["python3", "/opt/moneycraft/backup/scheduler.py", "/opt/moneycraft/backup/crontab"]
