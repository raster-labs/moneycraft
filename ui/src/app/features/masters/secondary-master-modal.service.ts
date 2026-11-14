import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { NavigationStackStore } from '../../state/navigation-stack.store';

export type SecondaryMasterKind =
  | 'GROUP'
  | 'LEDGER'
  | 'COST_CENTRE'
  | 'CURRENCY'
  | 'UOM'
  | 'GODOWN'
  | 'STOCK_GROUP'
  | 'STOCK_ITEM';

export const SECONDARY_MASTER_ROUTE_TOKEN = '__secondary_master__';

export interface SecondaryCreatedMaster {
  readonly id: string;
  readonly name: string;
  readonly kind: SecondaryMasterKind;
}

export interface OpenSecondaryMasterRequest {
  readonly kind: SecondaryMasterKind;
  readonly initialName?: string;
  readonly defaultGroupId?: string | null;
  readonly defaultParentGroupId?: string | null;
  readonly defaultCostCategoryId?: string | null;
  readonly defaultStockGroupId?: string | null;
  readonly defaultBaseUomId?: string | null;
  readonly onCreated?: (created: SecondaryCreatedMaster) => void;
}

export interface SecondaryMasterSession {
  readonly sessionId: string;
  readonly navFrameId: string;
  readonly kind: SecondaryMasterKind;
  readonly title: string;
  readonly initialName: string;
  readonly defaultGroupId: string | null;
  readonly defaultParentGroupId: string | null;
  readonly defaultCostCategoryId: string | null;
  readonly defaultStockGroupId: string | null;
  readonly defaultBaseUomId: string | null;
  readonly onCreated?: (created: SecondaryCreatedMaster) => void;
  readonly resolve: (result: SecondaryCreatedMaster | null) => void;
}

function titleForKind(kind: SecondaryMasterKind): string {
  switch (kind) {
    case 'GROUP':
      return 'Group Creation (Secondary)';
    case 'LEDGER':
      return 'Ledger Creation (Secondary)';
    case 'COST_CENTRE':
      return 'Cost Centre Creation (Secondary)';
    case 'CURRENCY':
      return 'Currency Creation (Secondary)';
    case 'UOM':
      return 'Unit Creation (Secondary)';
    case 'GODOWN':
      return 'Godown Creation (Secondary)';
    case 'STOCK_GROUP':
      return 'Stock Group Creation (Secondary)';
    case 'STOCK_ITEM':
      return 'Stock Item Creation (Secondary)';
  }
}

@Injectable({ providedIn: 'root' })
export class SecondaryMasterModalService {
  private readonly navStack = inject(NavigationStackStore);

  private readonly sessionsSignal = signal<readonly SecondaryMasterSession[]>([]);
  private nextSessionCounter = 1;

  readonly sessions = this.sessionsSignal.asReadonly();
  readonly isOpen = computed(() => this.sessionsSignal().length > 0);
  readonly activeSession = computed<SecondaryMasterSession | null>(() => {
    const list = this.sessionsSignal();
    return list.length > 0 ? list[list.length - 1] : null;
  });

  constructor() {
    // Keep secondary sessions strictly synchronized if user pops via global Esc
    effect(() => {
      const activeFrameIds = new Set(this.navStack.frames().map((f) => f.id));
      const currentSessions = this.sessionsSignal();
      const orphaned = currentSessions.filter((s) => !activeFrameIds.has(s.navFrameId));
      if (orphaned.length > 0) {
        for (const s of orphaned) {
          s.resolve(null);
        }
        this.sessionsSignal.set(currentSessions.filter((s) => activeFrameIds.has(s.navFrameId)));
      }
    });
  }

  open(request: OpenSecondaryMasterRequest): Promise<SecondaryCreatedMaster | null> {
    const title = titleForKind(request.kind);
    const frame = this.navStack.push({
      title,
      route: SECONDARY_MASTER_ROUTE_TOKEN,
      params: { kind: request.kind },
    });

    if (!frame) {
      return Promise.resolve(null);
    }

    return new Promise<SecondaryCreatedMaster | null>((resolve) => {
      const session: SecondaryMasterSession = {
        sessionId: `sec-master-${this.nextSessionCounter++}`,
        navFrameId: frame.id,
        kind: request.kind,
        title,
        initialName: request.initialName ?? '',
        defaultGroupId: request.defaultGroupId ?? null,
        defaultParentGroupId: request.defaultParentGroupId ?? null,
        defaultCostCategoryId: request.defaultCostCategoryId ?? null,
        defaultStockGroupId: request.defaultStockGroupId ?? null,
        defaultBaseUomId: request.defaultBaseUomId ?? null,
        onCreated: request.onCreated,
        resolve,
      };
      this.sessionsSignal.set([...this.sessionsSignal(), session]);
    });
  }

  completeActiveSession(created: SecondaryCreatedMaster): void {
    const top = this.activeSession();
    if (!top) {
      return;
    }
    this.sessionsSignal.set(this.sessionsSignal().filter((s) => s.sessionId !== top.sessionId));

    if (this.navStack.topFrame()?.id === top.navFrameId) {
      this.navStack.pop();
    }
    top.onCreated?.(created);
    top.resolve(created);
  }

  cancelActiveSession(): void {
    const top = this.activeSession();
    if (!top) {
      return;
    }
    this.sessionsSignal.set(this.sessionsSignal().filter((s) => s.sessionId !== top.sessionId));
    if (this.navStack.topFrame()?.id === top.navFrameId) {
      this.navStack.pop();
    }
    top.resolve(null);
  }
}
