import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { catchError, throwError } from 'rxjs';

export interface McProblemDetails {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail: string;
  readonly code: string;
  readonly instance?: string;
  readonly fieldErrors?: Readonly<Record<string, string>>;
}

export class McProblemError extends Error {
  constructor(public readonly problem: McProblemDetails) {
    super(`${problem.code}: ${problem.detail || problem.title}`);
    this.name = 'McProblemError';
  }
}

@Injectable({ providedIn: 'root' })
export class ProblemFeedbackStore {
  readonly latestProblem = signal<McProblemDetails | null>(null);
  readonly quarantineDetected = signal<boolean>(false);

  record(problem: McProblemDetails): void {
    this.latestProblem.set(problem);
    if (problem.status === 503 && problem.code === 'MC-SYSTEM-QUARANTINED') {
      this.quarantineDetected.set(true);
    }
  }

  dismiss(): void {
    this.latestProblem.set(null);
  }
}

/**
 * Normalizes RFC 9457 `ProblemDetail` HTTP errors (`MC-<CONTEXT>-<NNN>`) and
 * triggers Quarantine DR state detection on `503 MC-SYSTEM-QUARANTINED`.
 */
export const problemDetailsInterceptor: HttpInterceptorFn = (req, next) => {
  const feedbackStore = inject(ProblemFeedbackStore);

  return next(req).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse) {
        const body = (err.error ?? {}) as Record<string, unknown>;
        const rawCode =
          typeof body['code'] === 'string'
            ? body['code']
            : typeof body['errorCode'] === 'string'
              ? body['errorCode']
              : null;
        const problem: McProblemDetails = {
          type: typeof body['type'] === 'string' ? body['type'] : 'about:blank',
          title: typeof body['title'] === 'string' ? body['title'] : err.statusText || 'Request Failed',
          status: typeof body['status'] === 'number' ? body['status'] : err.status,
          detail:
            typeof body['detail'] === 'string'
              ? body['detail']
              : typeof body['message'] === 'string'
                ? body['message']
                : err.message,
          code:
            rawCode ??
            (err.status === 412
              ? 'MC-CONCURRENCY-STALE-VERSION'
              : err.status === 409
                ? 'MC-IDEMPOTENCY-CONFLICT'
                : `MC-HTTP-${err.status || 0}`),
          instance: typeof body['instance'] === 'string' ? body['instance'] : req.url,
          fieldErrors:
            typeof body['fieldErrors'] === 'object' && body['fieldErrors'] !== null
              ? (body['fieldErrors'] as Record<string, string>)
              : undefined,
        };
        feedbackStore.record(problem);
        return throwError(() => new McProblemError(problem));
      }
      return throwError(() => err);
    }),
  );
};
