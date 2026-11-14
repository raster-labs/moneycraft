import {
  provideHttpClient,
  withFetch,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { APP_ROUTES } from './app.routes';
import { etagInterceptor } from './core/http/etag.interceptor';
import { idempotencyInterceptor } from './core/http/idempotency.interceptor';
import { problemDetailsInterceptor } from './core/http/problem-details.interceptor';
import { RuntimeConfigService } from './core/http/runtime-config.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideRouter(APP_ROUTES, withComponentInputBinding()),
    provideHttpClient(
      withFetch(),
      withXsrfConfiguration({
        cookieName: 'XSRF-TOKEN',
        headerName: 'X-XSRF-TOKEN',
      }),
      withInterceptors([idempotencyInterceptor, etagInterceptor, problemDetailsInterceptor]),
    ),
    provideAppInitializer(() => inject(RuntimeConfigService).loadConfig()),
  ],
};
