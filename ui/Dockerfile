# syntax=docker/dockerfile:1.7
# ==============================================================================
# MoneyCraft 1.0.0 — Hardened Multi-Stage Angular 22 LTS SPA (`moneycraft-ui`)
# Stage 1: `node:24.18-bookworm-slim` AOT build + `.gz`/`.br` pre-compression
# Stage 2: Compile `ngx_brotli` dynamic modules matching `nginx:1.28.x`
# Stage 3: `nginxinc/nginx-unprivileged:1.28-alpine` (`uid 101:101`, read-only)
# ==============================================================================

FROM node:24.18-bookworm-slim@sha256:235600a8101ab264e117b1768e925532262668dc9b581ef1dd7d96ced463b8e7 AS ui-builder

RUN apt-get update && \
    apt-get install -y --no-install-recommends brotli gzip && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --ignore-scripts

COPY angular.json tsconfig.json tsconfig.app.json tsconfig.spec.json eslint.config.mjs ./
COPY public ./public
COPY src ./src

RUN npm run build:prod && \
    mkdir -p /workspace/dist/moneycraft-ui/browser/assets/config && \
    # Pre-compress static fingerprinted bundles (.gz + .br) while keeping index.html
    # uncompressed so Nginx sub_filter injects fresh per-request $request_id CSP nonces
    find /workspace/dist/moneycraft-ui/browser \
        -type f \
        \( -name '*.js' -o -name '*.css' -o -name '*.svg' -o -name '*.json' -o -name '*.webmanifest' -o -name '*.txt' \) \
        ! -name 'runtime-config.json' \
        -exec gzip -9 -k -f {} \; \
        -exec brotli -q 11 -k -f {} \;

# ==============================================================================
# Stage 2: Compile `ngx_http_brotli` Dynamic Modules for Exact Nginx Version
# ==============================================================================
FROM nginxinc/nginx-unprivileged:1.28-alpine@sha256:7377697a821c131a924a7105fafbe7414db4e9fcc77a6f08f776f33f141ec3f8 AS brotli-builder

USER root

RUN NGX_VER="$(nginx -v 2>&1 | sed 's/.*nginx\///')" && \
    apk add --no-cache \
        brotli-dev \
        curl \
        gcc \
        libc-dev \
        linux-headers \
        make \
        pcre2-dev \
        tar \
        zlib-dev && \
    mkdir -p /tmp/src && \
    curl -fsSL "https://nginx.org/download/nginx-${NGX_VER}.tar.gz" | tar -xz -C /tmp/src && \
    curl -fsSL "https://github.com/google/ngx_brotli/archive/refs/tags/v1.0.0rc.tar.gz" | tar -xz -C /tmp/src && \
    cd "/tmp/src/nginx-${NGX_VER}" && \
    ./configure --with-compat --add-dynamic-module=/tmp/src/ngx_brotli-1.0.0rc && \
    make modules && \
    mkdir -p /out-modules && \
    cp objs/ngx_http_brotli_filter_module.so objs/ngx_http_brotli_static_module.so /out-modules/

# ==============================================================================
# Stage 3: Hardened Unprivileged Nginx Runtime (`uid 101:101`)
# ==============================================================================
FROM nginxinc/nginx-unprivileged:1.28-alpine@sha256:7377697a821c131a924a7105fafbe7414db4e9fcc77a6f08f776f33f141ec3f8 AS runtime

ARG MC_VERSION=1.0.0-SNAPSHOT
ARG MC_VCS_REF=dev

LABEL org.opencontainers.image.title="moneycraft-ui" \
      org.opencontainers.image.description="MoneyCraft 1.0.0 Angular 22 LTS Keyboard-First ERP SPA" \
      org.opencontainers.image.version="${MC_VERSION}" \
      org.opencontainers.image.revision="${MC_VCS_REF}" \
      org.opencontainers.image.vendor="Raster Images Pvt. Ltd."

USER root

RUN apk add --no-cache brotli-libs curl && \
    rm -rf /etc/nginx/conf.d/* /usr/share/nginx/html/* && \
    mkdir -p /usr/share/nginx/html/assets/config && \
    chown -R 101:101 /usr/share/nginx/html

COPY --from=brotli-builder /out-modules/ngx_http_brotli_filter_module.so /usr/lib/nginx/modules/
COPY --from=brotli-builder /out-modules/ngx_http_brotli_static_module.so /usr/lib/nginx/modules/

COPY --chown=101:101 nginx/nginx.conf /etc/nginx/nginx.conf
COPY --chown=101:101 nginx/security-headers.conf /etc/nginx/security-headers.conf
COPY --chown=101:101 nginx/40-inject-runtime-config.sh /docker-entrypoint.d/40-inject-runtime-config.sh
RUN chmod 0755 /docker-entrypoint.d/40-inject-runtime-config.sh

COPY --from=ui-builder --chown=101:101 /workspace/dist/moneycraft-ui/browser/ /usr/share/nginx/html/

ENV MC_PUBLIC_API_URL="/api/v1" \
    MC_ENV_LABEL="PRODUCTION" \
    MC_DEFAULT_CURRENCY="INR" \
    MC_RELEASE_VERSION="1.0.0"

USER 101:101

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/healthz >/dev/null || exit 1
