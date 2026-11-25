import { Injectable, signal } from '@angular/core';

export interface BackendEndpointConfig {
  readonly scheme: 'https' | 'http';
  readonly host: string;
  readonly port: string;
  readonly basePath: string;
}

export interface RuntimeConfig {
  readonly apiBaseUrl: string;
  readonly envLabel: string;
  readonly defaultCurrency: string;
  readonly releaseVersion: string;
  readonly backendHost?: string;
  readonly backendPort?: string;
  readonly backendScheme?: 'https' | 'http';
}

export const MC_BACKEND_ENDPOINT_STORAGE_KEY = 'mc.backend.endpoint.v1';

const DEFAULT_RUNTIME_CONFIG: RuntimeConfig = {
  apiBaseUrl: '/api/v1',
  envLabel: 'LOCAL',
  defaultCurrency: 'INR',
  releaseVersion: '1.0.0',
};

@Injectable({ providedIn: 'root' })
export class RuntimeConfigService {
  private readonly serverDefaultConfigSignal = signal<RuntimeConfig>(DEFAULT_RUNTIME_CONFIG);
  private readonly customEndpointSignal = signal<BackendEndpointConfig | null>(
    this.readStoredCustomEndpoint(),
  );
  private readonly configSignal = signal<RuntimeConfig>(
    this.composeEffectiveConfig(DEFAULT_RUNTIME_CONFIG, this.customEndpointSignal()),
  );
  readonly config = this.configSignal.asReadonly();
  readonly customEndpoint = this.customEndpointSignal.asReadonly();

  get apiBaseUrl(): string {
    return this.configSignal().apiBaseUrl;
  }

  hasCustomEndpointOverride(): boolean {
    return this.customEndpointSignal() !== null;
  }

  url(path: string): string {
    const base = this.apiBaseUrl.replace(/\/+$/, '');
    const normalizedPath = path.startsWith('/') ? path : `/${path}`;
    return `${base}${normalizedPath}`;
  }

  currentEndpointParts(): BackendEndpointConfig {
    const custom = this.customEndpointSignal();
    if (custom) {
      return custom;
    }
    const current = this.configSignal();
    const parsedFromUrl = this.tryParseAbsoluteApiUrl(current.apiBaseUrl);
    if (parsedFromUrl) {
      return parsedFromUrl;
    }
    return {
      scheme: current.backendScheme === 'http' ? 'http' : 'https',
      host:
        current.backendHost && current.backendHost.trim().length > 0
          ? current.backendHost.trim()
          : typeof window !== 'undefined' && window.location?.hostname
            ? window.location.hostname
            : 'localhost',
      port:
        current.backendPort && current.backendPort.trim().length > 0
          ? current.backendPort.trim()
          : typeof window !== 'undefined' && window.location?.port
            ? window.location.port
            : '8443',
      basePath: '/api/v1',
    };
  }

  setBackendHostAndPort(
    host: string,
    port: string | number,
    scheme: 'https' | 'http' = 'https',
    basePath = '/api/v1',
  ): RuntimeConfig {
    const normalizedScheme: 'https' | 'http' = scheme === 'http' ? 'http' : 'https';
    const trimmedHost = (host ?? '').trim().replace(/^\[|\]$/g, '');
    if (
      !trimmedHost ||
      /[\s/?#@]/.test(trimmedHost) ||
      trimmedHost.toLowerCase().startsWith('jdbc:') ||
      trimmedHost.toLowerCase().startsWith('postgres')
    ) {
      throw new Error('Enter a valid backend IP address or hostname (e.g. 192.168.1.100 or localhost).');
    }

    const numericPort = typeof port === 'number' ? port : Number(String(port ?? '').trim());
    if (!Number.isInteger(numericPort) || numericPort < 1 || numericPort > 65535) {
      throw new Error('Enter a valid backend TCP port between 1 and 65535 (e.g. 8443 or 8080).');
    }

    const normalizedBasePath = this.normalizeBasePath(basePath);
    const endpoint: BackendEndpointConfig = {
      scheme: normalizedScheme,
      host: trimmedHost,
      port: String(numericPort),
      basePath: normalizedBasePath,
    };

    this.writeStoredCustomEndpoint(endpoint);
    this.customEndpointSignal.set(endpoint);
    const nextConfig = this.composeEffectiveConfig(this.serverDefaultConfigSignal(), endpoint);
    this.configSignal.set(nextConfig);
    return nextConfig;
  }

  resetBackendEndpointToDefault(): RuntimeConfig {
    this.writeStoredCustomEndpoint(null);
    this.customEndpointSignal.set(null);
    const restored = this.composeEffectiveConfig(this.serverDefaultConfigSignal(), null);
    this.configSignal.set(restored);
    return restored;
  }

  async loadConfig(): Promise<void> {
    if (typeof fetch !== 'function') {
      return;
    }

    try {
      const response = await fetch('assets/config/runtime-config.json', {
        cache: 'no-store',
        credentials: 'same-origin',
      });
      if (!response.ok) {
        return;
      }
      const parsed = (await response.json()) as Partial<RuntimeConfig>;
      const backendScheme: 'https' | 'http' = parsed.backendScheme === 'http' ? 'http' : 'https';
      const backendHost =
        typeof parsed.backendHost === 'string' && parsed.backendHost.trim().length > 0
          ? parsed.backendHost.trim()
          : undefined;
      const backendPort =
        typeof parsed.backendPort === 'string' && parsed.backendPort.trim().length > 0
          ? parsed.backendPort.trim()
          : undefined;

      let resolvedApiBaseUrl =
        typeof parsed.apiBaseUrl === 'string' && parsed.apiBaseUrl.trim().length > 0
          ? parsed.apiBaseUrl.trim()
          : DEFAULT_RUNTIME_CONFIG.apiBaseUrl;

      if (resolvedApiBaseUrl === '/api/v1' && backendHost && backendPort) {
        const formattedHost = backendHost.includes(':') && !backendHost.startsWith('[')
          ? `[${backendHost}]`
          : backendHost;
        resolvedApiBaseUrl = `${backendScheme}://${formattedHost}:${backendPort}/api/v1`;
      }

      const loadedDefault: RuntimeConfig = {
        apiBaseUrl: resolvedApiBaseUrl,
        envLabel:
          typeof parsed.envLabel === 'string' && parsed.envLabel.trim().length > 0
            ? parsed.envLabel.trim()
            : DEFAULT_RUNTIME_CONFIG.envLabel,
        defaultCurrency:
          typeof parsed.defaultCurrency === 'string' && parsed.defaultCurrency.trim().length > 0
            ? parsed.defaultCurrency.trim()
            : DEFAULT_RUNTIME_CONFIG.defaultCurrency,
        releaseVersion:
          typeof parsed.releaseVersion === 'string' && parsed.releaseVersion.trim().length > 0
            ? parsed.releaseVersion.trim()
            : DEFAULT_RUNTIME_CONFIG.releaseVersion,
        ...(backendHost ? { backendHost } : {}),
        ...(backendPort ? { backendPort } : {}),
        ...(parsed.backendScheme ? { backendScheme } : {}),
      };
      this.serverDefaultConfigSignal.set(loadedDefault);
      this.configSignal.set(this.composeEffectiveConfig(loadedDefault, this.customEndpointSignal()));
    } catch {
      // Retain offline-safe default `/api/v1` fallback
    }
  }

  private composeEffectiveConfig(
    baseConfig: RuntimeConfig,
    custom: BackendEndpointConfig | null,
  ): RuntimeConfig {
    if (!custom) {
      return baseConfig;
    }
    const formattedHost =
      custom.host.includes(':') && !custom.host.startsWith('[') ? `[${custom.host}]` : custom.host;
    const customApiUrl = `${custom.scheme}://${formattedHost}:${custom.port}${custom.basePath}`;
    return {
      ...baseConfig,
      apiBaseUrl: customApiUrl,
      backendScheme: custom.scheme,
      backendHost: custom.host,
      backendPort: custom.port,
    };
  }

  private normalizeBasePath(basePath: string): string {
    const trimmed = (basePath ?? '').trim();
    if (!trimmed) {
      return '/api/v1';
    }
    const withLeadingSlash = trimmed.startsWith('/') ? trimmed : `/${trimmed}`;
    return withLeadingSlash.replace(/\/+$/, '') || '/api/v1';
  }

  private tryParseAbsoluteApiUrl(rawUrl: string): BackendEndpointConfig | null {
    if (!rawUrl || (!rawUrl.startsWith('https://') && !rawUrl.startsWith('http://'))) {
      return null;
    }
    try {
      const parsed = new URL(rawUrl);
      const scheme: 'https' | 'http' = parsed.protocol === 'http:' ? 'http' : 'https';
      const host = parsed.hostname.replace(/^\[|\]$/g, '');
      const port = parsed.port || (scheme === 'http' ? '80' : '443');
      const basePath = this.normalizeBasePath(parsed.pathname);
      return { scheme, host, port, basePath };
    } catch {
      return null;
    }
  }

  private readStoredCustomEndpoint(): BackendEndpointConfig | null {
    try {
      if (typeof localStorage === 'undefined') {
        return null;
      }
      const raw = localStorage.getItem(MC_BACKEND_ENDPOINT_STORAGE_KEY);
      if (!raw) {
        return null;
      }
      const parsed = JSON.parse(raw) as Partial<BackendEndpointConfig>;
      if (
        typeof parsed.host === 'string' &&
        parsed.host.trim().length > 0 &&
        typeof parsed.port === 'string' &&
        /^\d+$/.test(parsed.port.trim())
      ) {
        const portNum = Number(parsed.port.trim());
        if (portNum >= 1 && portNum <= 65535) {
          return {
            scheme: parsed.scheme === 'http' ? 'http' : 'https',
            host: parsed.host.trim().replace(/^\[|\]$/g, ''),
            port: String(portNum),
            basePath: this.normalizeBasePath(parsed.basePath ?? '/api/v1'),
          };
        }
      }
    } catch {
      // Ignore storage access errors in private/sandboxed contexts
    }
    return null;
  }

  private writeStoredCustomEndpoint(endpoint: BackendEndpointConfig | null): void {
    try {
      if (typeof localStorage === 'undefined') {
        return;
      }
      if (!endpoint) {
        localStorage.removeItem(MC_BACKEND_ENDPOINT_STORAGE_KEY);
      } else {
        localStorage.setItem(MC_BACKEND_ENDPOINT_STORAGE_KEY, JSON.stringify(endpoint));
      }
    } catch {
      // Ignore storage write errors in restricted contexts
    }
  }
}
