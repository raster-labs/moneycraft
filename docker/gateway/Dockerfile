# syntax=docker/dockerfile:1.7
# ==============================================================================
# MoneyCraft 1.0.0 — Hardened Edge Reverse Proxy (`moneycraft-gateway`)
# Non-root `101:101`, TLS 1.3, Rate Limiting, Unbuffered SSE, `/actuator` Block
# ==============================================================================

FROM nginxinc/nginx-unprivileged:1.28-alpine@sha256:7377697a821c131a924a7105fafbe7414db4e9fcc77a6f08f776f33f141ec3f8

ARG MC_VERSION=1.0.0-SNAPSHOT
ARG MC_VCS_REF=dev

LABEL org.opencontainers.image.title="moneycraft-gateway" \
      org.opencontainers.image.description="MoneyCraft 1.0.0 Hardened TLS 1.3 Edge Proxy" \
      org.opencontainers.image.version="${MC_VERSION}" \
      org.opencontainers.image.revision="${MC_VCS_REF}"

USER root

RUN apk add --no-cache curl openssl && \
    rm -f /etc/nginx/conf.d/default.conf && \
    cat > /etc/nginx/nginx.conf <<'EOF'
worker_processes auto;
pid /tmp/nginx.pid;
error_log /dev/stderr warn;

events {
    worker_connections 2048;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    server_tokens off;
    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;
    client_max_body_size 64m;

    client_body_temp_path /var/cache/nginx/client_temp;
    proxy_temp_path /var/cache/nginx/proxy_temp;
    fastcgi_temp_path /var/cache/nginx/fastcgi_temp;
    uwsgi_temp_path /var/cache/nginx/uwsgi_temp;
    scgi_temp_path /var/cache/nginx/scgi_temp;

    log_format main '$remote_addr - [$time_local] "$request" $status $body_bytes_sent '
                    'rt=$request_time req_id=$request_id';
    access_log /dev/stdout main;

    include /tmp/nginx-conf.d/*.conf;
}
EOF

RUN cat > /docker-entrypoint.d/05-prepare-gateway-tls.sh <<'EOF' && \
    chmod 0755 /docker-entrypoint.d/05-prepare-gateway-tls.sh
#!/bin/sh
set -eu

mkdir -p /tmp/nginx-conf.d /tmp/tls

CERT_SRC="${MC_TLS_CERT_FILE:-/run/secrets/mc_tls_fullchain}"
KEY_SRC="${MC_TLS_KEY_FILE:-/run/secrets/mc_tls_privkey}"

if [ -r "${CERT_SRC}" ] && [ -r "${KEY_SRC}" ]; then
    cp "${CERT_SRC}" /tmp/tls/fullchain.pem
    cp "${KEY_SRC}" /tmp/tls/privkey.pem
    chmod 0600 /tmp/tls/privkey.pem
else
    if [ "${MC_ALLOW_SELF_SIGNED_TLS:-false}" != "true" ]; then
        echo "[FATAL] TLS certificate/key missing at ${CERT_SRC} / ${KEY_SRC} and MC_ALLOW_SELF_SIGNED_TLS!=true" >&2
        exit 1
    fi
    CN_HOST="${MC_PUBLIC_HOSTNAME:-moneycraft.local.test}"
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
        -days 30 -nodes \
        -keyout /tmp/tls/privkey.pem \
        -out /tmp/tls/fullchain.pem \
        -subj "/CN=${CN_HOST}/O=MoneyCraft Local Dev" \
        -addext "subjectAltName=DNS:${CN_HOST},DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1
    chmod 0600 /tmp/tls/privkey.pem
fi

if [ "${MC_DEV_ALLOW_HTTP:-false}" = "true" ]; then
    cat > /tmp/nginx-conf.d/http-port8080-mode.inc <<'HTTPDEV'
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;

limit_conn mc_conn_limit 64;

include /tmp/nginx-conf.d/gateway-routes.inc;
HTTPDEV
else
    REDIRECT_PORT="${MC_HTTPS_REDIRECT_PORT:-443}"
    cat > /tmp/nginx-conf.d/http-port8080-mode.inc <<HTTPPROD
location / {
    return 308 https://\$host:${REDIRECT_PORT}\$request_uri;
}
HTTPPROD
fi
EOF

COPY --chown=101:101 gateway-routes.inc.template /etc/nginx/templates/gateway-routes.inc.template
COPY --chown=101:101 nginx.conf.template /etc/nginx/templates/default.conf.template

ENV NGINX_ENVSUBST_OUTPUT_DIR="/tmp/nginx-conf.d" \
    NGINX_ENVSUBST_FILTER="^(MC_API_UPSTREAM|MC_UI_UPSTREAM|MC_PUBLIC_HOSTNAME|MC_HTTPS_REDIRECT_PORT)$" \
    MC_API_UPSTREAM="moneycraft-api:8080" \
    MC_UI_UPSTREAM="moneycraft-ui:8080" \
    MC_PUBLIC_HOSTNAME="localhost" \
    MC_HTTPS_REDIRECT_PORT="443" \
    MC_DEV_ALLOW_HTTP="false"

USER 101:101

EXPOSE 8080 8443

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/gateway-healthz >/dev/null || exit 1
