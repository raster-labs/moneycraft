import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { tap } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class EtagCacheService {
  private readonly etagsByUrl = new Map<string, string>();

  private normalizeUrl(url: string): string {
    return url.split('?')[0];
  }

  record(url: string, etag: string | null): void {
    if (etag && etag.trim().length > 0) {
      this.etagsByUrl.set(this.normalizeUrl(url), etag.trim());
    }
  }

  get(url: string): string | undefined {
    return this.etagsByUrl.get(this.normalizeUrl(url));
  }

  clear(): void {
    this.etagsByUrl.clear();
  }
}

const CONCURRENCY_GUARDED_METHODS = new Set(['PUT', 'PATCH', 'DELETE']);

/**
 * Tracks resource `ETag` response headers and automatically supplies `If-Match`
 * on optimistic-concurrency mutations (`PUT`, `PATCH`, `DELETE`).
 */
export const etagInterceptor: HttpInterceptorFn = (req, next) => {
  const cache = inject(EtagCacheService);
  let outgoing = req.clone({ withCredentials: true });

  if (CONCURRENCY_GUARDED_METHODS.has(req.method.toUpperCase()) && !req.headers.has('If-Match')) {
    const knownEtag = cache.get(req.url);
    if (knownEtag) {
      outgoing = outgoing.clone({
        setHeaders: {
          'If-Match': knownEtag,
        },
      });
    }
  }

  return next(outgoing).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) {
        const etag = event.headers.get('ETag');
        if (etag) {
          cache.record(req.url, etag);
        }
      }
    }),
  );
};
