import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { CompanyContextStore } from '../../state/company-context.store';
import { NavigationStackStore } from '../../state/navigation-stack.store';
import { EditLogEntityType } from './statutory.models';

export const EDIT_LOG_ROUTE_TOKEN = '__edit_log__';

export interface OpenEditLogRequest {
  readonly entityType: EditLogEntityType;
  readonly entityId: string;
  readonly entityLabel: string;
  readonly companyId?: string;
}

export interface EditLogModalSession {
  readonly sessionId: string;
  readonly navFrameId: string;
  readonly companyId: string;
  readonly entityType: EditLogEntityType;
  readonly entityId: string;
  readonly entityLabel: string;
}

@Injectable({ providedIn: 'root' })
export class EditLogModalService {
  private readonly navStack = inject(NavigationStackStore);
  private readonly companyContext = inject(CompanyContextStore);

  private readonly sessionsSignal = signal<readonly EditLogModalSession[]>([]);
  private nextCounter = 1;

  readonly sessions = this.sessionsSignal.asReadonly();
  readonly isOpen = computed(() => this.sessionsSignal().length > 0);
  readonly activeSession = computed<EditLogModalSession | null>(() => {
    const list = this.sessionsSignal();
    return list.length > 0 ? list[list.length - 1] : null;
  });

  constructor() {
    effect(() => {
      const activeFrameIds = new Set(this.navStack.frames().map((f) => f.id));
      const current = this.sessionsSignal();
      if (current.some((s) => !activeFrameIds.has(s.navFrameId))) {
        this.sessionsSignal.set(current.filter((s) => activeFrameIds.has(s.navFrameId)));
      }
    });
  }

  open(request: OpenEditLogRequest): boolean {
    const companyId = request.companyId ?? this.companyContext.activeCompany()?.id ?? '';
    if (!companyId || !request.entityId) {
      return false;
    }

    const title = `Edit Log — ${request.entityLabel}`;
    const frame = this.navStack.push({
      title,
      route: EDIT_LOG_ROUTE_TOKEN,
      params: {
        entityType: request.entityType,
        entityId: request.entityId,
      },
    });
    if (!frame) {
      return false;
    }

    const session: EditLogModalSession = {
      sessionId: `edit-log-${this.nextCounter++}`,
      navFrameId: frame.id,
      companyId,
      entityType: request.entityType,
      entityId: request.entityId,
      entityLabel: request.entityLabel,
    };
    this.sessionsSignal.set([...this.sessionsSignal(), session]);
    return true;
  }

  close(): void {
    const top = this.activeSession();
    if (!top) {
      return;
    }
    this.sessionsSignal.set(this.sessionsSignal().filter((s) => s.sessionId !== top.sessionId));
    if (this.navStack.topFrame()?.id === top.navFrameId) {
      this.navStack.pop();
    }
  }
}
