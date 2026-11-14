#!/usr/bin/env bash
# ==============================================================================
# MoneyCraft 1.0.0 — Least-Privilege PostgreSQL 18 Role Bootstrap
# Mounted at `/docker-entrypoint-initdb.d/10-init-least-privilege-roles.sh`
# Creates:
#   1. `mc_flyway_migrator` — Schema DDL owner used solely by `moneycraft-migrator`
#   2. `mc_app_runtime`     — `NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS`
#                             runtime role enforced on `moneycraft-api`
#   3. `mc_backup_operator` — Physical `REPLICATION` + read-only `pg_read_all_data`
#                             role used by `moneycraft-pg-backup`
# ==============================================================================
set -euo pipefail

read_secret_or_env() {
    local file_var="$1"
    local plain_var="$2"
    local file_path="${!file_var:-}"
    if [[ -n "${file_path}" && -r "${file_path}" ]]; then
        tr -d '\r\n' < "${file_path}"
        return 0
    fi
    local plain_val="${!plain_var:-}"
    if [[ -n "${plain_val}" ]]; then
        printf '%s' "${plain_val}"
        return 0
    fi
    echo "[FATAL] Missing secret in ${file_var} or ${plain_var}" >&2
    exit 1
}

MIGRATOR_PW="$(read_secret_or_env MC_DB_MIGRATOR_PASSWORD_FILE MC_DB_MIGRATOR_PASSWORD)"
RUNTIME_PW="$(read_secret_or_env MC_DB_RUNTIME_PASSWORD_FILE MC_DB_RUNTIME_PASSWORD)"
BACKUP_PW="$(read_secret_or_env MC_DB_BACKUP_PASSWORD_FILE MC_DB_BACKUP_PASSWORD)"

mkdir -p /var/lib/postgresql/wal-archive/business /var/lib/postgresql/wal-archive/authority

psql -v ON_ERROR_STOP=1 \
     --username "${POSTGRES_USER}" \
     --dbname "${POSTGRES_DB}" \
     -v migrator_pw="${MIGRATOR_PW}" \
     -v runtime_pw="${RUNTIME_PW}" \
     -v backup_pw="${BACKUP_PW}" \
     -v target_db="${POSTGRES_DB}" <<'EOSQL'
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mc_flyway_migrator') THEN
        CREATE ROLE mc_flyway_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mc_app_runtime') THEN
        CREATE ROLE mc_app_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mc_backup_operator') THEN
        CREATE ROLE mc_backup_operator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE REPLICATION NOBYPASSRLS;
    END IF;
END
$$;

ALTER ROLE mc_flyway_migrator WITH PASSWORD :'migrator_pw';
ALTER ROLE mc_app_runtime     WITH PASSWORD :'runtime_pw';
ALTER ROLE mc_backup_operator WITH PASSWORD :'backup_pw';

GRANT pg_read_all_data TO mc_backup_operator;
GRANT pg_checkpoint    TO mc_backup_operator;

REVOKE ALL ON DATABASE :"target_db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"target_db" TO mc_flyway_migrator, mc_app_runtime, mc_backup_operator;
GRANT CREATE ON DATABASE :"target_db" TO mc_flyway_migrator;

GRANT USAGE, CREATE ON SCHEMA public TO mc_flyway_migrator;
GRANT USAGE ON SCHEMA public TO mc_app_runtime, mc_backup_operator;

ALTER DEFAULT PRIVILEGES FOR ROLE mc_flyway_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mc_app_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE mc_flyway_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO mc_app_runtime;
EOSQL
