import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { AuthStore } from '../auth/auth.store';
import { RuntimeConfigService } from './runtime-config.service';

export type McOperationalEventType =
  | 'QUARANTINE_CHANGED'
  | 'APPROVAL_QUEUE_UPDATED'
  | 'MIGRATION_PROGRESS'
  | 'PDF_RENDER_COMPLETED'
  | 'CONNECTOR_SYNC_COMPLETED'
  | 'HEARTBEAT';

export interface McOperationalEvent {
  readonly eventId: string;
  readonly type: McOperationalEventType;
  readonly companyId?: string;
  readonly occurredAt: string;
  readonly payload: Readonly<Record<string, unknown>>;
}

@Injectable({ providedIn: 'root' })
export class SseStreamService {
  private readonly runtimeConfig = inject(RuntimeConfigService);
  private readonly authStore = inject(AuthStore);
  private readonly destroyRef = inject(DestroyRef);

  private eventSource: EventSource | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempt = 0;

  private readonly eventsSubject = new Subject<McOperationalEvent>();
  readonly events$ = this.eventsSubject.asObservable();
  readonly connected = signal<boolean>(false);
  readonly lastEvent = signal<McOperationalEvent | null>(null);

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.disconnect();
    });
  }

  connect(): void {
    if (
      this.eventSource ||
      typeof EventSource === 'undefined' ||
      !this.authStore.isAuthenticated()
    ) {
      return;
    }

    const streamUrl = this.runtimeConfig.url('/events/stream');
    const source = new EventSource(streamUrl, { withCredentials: true });
    this.eventSource = source;

    source.onopen = () => {
      this.reconnectAttempt = 0;
      this.connected.set(true);
    };

    source.onmessage = (msg: MessageEvent<string>) => {
      try {
        const parsed = JSON.parse(msg.data) as McOperationalEvent;
        this.lastEvent.set(parsed);
        this.eventsSubject.next(parsed);
      } catch {
        // Ignore non-JSON keepalive frames
      }
    };

    source.onerror = () => {
      this.connected.set(false);
      source.close();
      this.eventSource = null;
      this.scheduleReconnect();
    };
  }

  disconnect(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.connected.set(false);
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer !== null) {
      return;
    }
    const backoffMs = Math.min(30_000, 1_000 * 2 ** Math.min(this.reconnectAttempt, 5));
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, backoffMs);
  }
}
