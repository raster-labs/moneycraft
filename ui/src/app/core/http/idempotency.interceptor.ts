import { HttpInterceptorFn } from '@angular/common/http';

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * Attaches an RFC 4122 UUIDv4 `Idempotency-Key` header on every mutating request
 * (`POST`, `PUT`, `PATCH`, `DELETE`) when not already provided by the caller (`MCJ1-RETRY-01`).
 */
export const idempotencyInterceptor: HttpInterceptorFn = (req, next) => {
  if (!MUTATING_METHODS.has(req.method.toUpperCase()) || req.headers.has('Idempotency-Key')) {
    return next(req);
  }

  return next(
    req.clone({
      withCredentials: true,
      setHeaders: {
        'Idempotency-Key': generateUuidV4(),
      },
    }),
  );
};
