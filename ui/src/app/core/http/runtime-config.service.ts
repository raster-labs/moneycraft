import { Injectable, signal } from '@angular/core';

export interface RuntimeConfig {
  readonly apiBaseUrl: string;
  readonly envLabel: string;
  readonly defaultCurrency: string;
  readonly releaseVersion: string;
}

const DEFAULT_RUNTIME_CONFIG: RuntimeConfig = {
  apiBaseUrl: '/api/v1',
  envLabel: 'LOCAL',
  defaultCurrency: 'INR',
  releaseVersion: '1.0.0',
};

@Injectable({ providedIn: 'root' })
export class RuntimeConfigService {
  private readonly configSignal = signal<RuntimeConfig>(DEFAULT_RUNTIME_CONFIG);
  readonly config = this.configSignal.asReadonly();

  get apiBaseUrl(): string {
    return this.configSignal().apiBaseUrl;
  }

  url(path: string): string {
    const base = this.apiBaseUrl.replace(/\/+$/, '');
    const normalizedPath = path.startsWith('/') ? path : `/${path}`;
    return `${base}${normalizedPath}`;
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
      this.configSignal.set({
        apiBaseUrl:
          typeof parsed.apiBaseUrl === 'string' && parsed.apiBaseUrl.trim().length > 0
            ? parsed.apiBaseUrl.trim()
            : DEFAULT_RUNTIME_CONFIG.apiBaseUrl,
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
      });
    } catch {
      // Retain offline-safe default `/api/v1` fallback
    }
  }
}
