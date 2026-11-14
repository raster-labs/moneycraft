import { DOCUMENT } from '@angular/common';
import { Injectable, computed, inject, signal } from '@angular/core';

export const MAX_NAVIGATION_STACK_DEPTH = 8;

export interface NavigationStackFrame {
  readonly id: string;
  readonly title: string;
  readonly route: string;
  readonly params: Readonly<Record<string, string>>;
  readonly scrollTop: number;
  readonly activeElementId: string | null;
  readonly draftSnapshot: unknown;
}

export interface PushNavigationOverlayInput {
  readonly title: string;
  readonly route: string;
  readonly params?: Readonly<Record<string, string>>;
  readonly scrollTop?: number;
  readonly activeElementId?: string | null;
  readonly draftSnapshot?: unknown;
}

@Injectable({ providedIn: 'root' })
export class NavigationStackStore {
  private readonly document = inject(DOCUMENT);

  private readonly framesSignal = signal<readonly NavigationStackFrame[]>([]);
  private readonly depthLimitReachedSignal = signal<boolean>(false);
  private nextFrameCounter = 1;
  private nextFocusAnchorCounter = 1;

  private draftSnapshotProvider: (() => unknown) | null = null;
  private draftSnapshotRestorer: ((snapshot: unknown) => void) | null = null;

  readonly frames = this.framesSignal.asReadonly();
  readonly depth = computed(() => this.framesSignal().length);
  readonly hasOverlays = computed(() => this.framesSignal().length > 0);
  readonly topFrame = computed<NavigationStackFrame | null>(() => {
    const stack = this.framesSignal();
    return stack.length > 0 ? stack[stack.length - 1] : null;
  });
  readonly isAtMaxDepth = computed(() => this.framesSignal().length >= MAX_NAVIGATION_STACK_DEPTH);
  readonly depthLimitReached = this.depthLimitReachedSignal.asReadonly();

  registerDraftLifecycleHooks(
    captureDraft: () => unknown,
    restoreDraft: (snapshot: unknown) => void,
  ): () => void {
    this.draftSnapshotProvider = captureDraft;
    this.draftSnapshotRestorer = restoreDraft;
    return () => {
      if (this.draftSnapshotProvider === captureDraft) {
        this.draftSnapshotProvider = null;
      }
      if (this.draftSnapshotRestorer === restoreDraft) {
        this.draftSnapshotRestorer = null;
      }
    };
  }

  /**
   * Pushes a non-destructive `Alt+G` (`Go To`) modal overlay up to depth 8,
   * capturing scroll position, active cell element ID, and dirty voucher/master draft state.
   */
  push(input: PushNavigationOverlayInput): NavigationStackFrame | null {
    const currentStack = this.framesSignal();
    if (currentStack.length >= MAX_NAVIGATION_STACK_DEPTH) {
      this.depthLimitReachedSignal.set(true);
      return null;
    }

    this.depthLimitReachedSignal.set(false);
    const activeElementId =
      input.activeElementId !== undefined ? input.activeElementId : this.captureActiveElementId();
    const scrollTop =
      input.scrollTop !== undefined ? input.scrollTop : this.captureWorkspaceScrollTop();
    const draftSnapshot =
      input.draftSnapshot !== undefined
        ? input.draftSnapshot
        : this.draftSnapshotProvider
          ? this.draftSnapshotProvider()
          : null;

    const frame: NavigationStackFrame = {
      id: `goto-frame-${this.nextFrameCounter++}`,
      title: input.title,
      route: input.route,
      params: input.params ?? {},
      scrollTop,
      activeElementId,
      draftSnapshot,
    };

    this.framesSignal.set([...currentStack, frame]);
    return frame;
  }

  /**
   * Pops the top `Alt+G` modal overlay on `Esc` and restores exact scroll offset,
   * cell focus, and unsaved draft state synchronously (`< 16 ms`).
   */
  pop(): NavigationStackFrame | null {
    const currentStack = this.framesSignal();
    if (currentStack.length === 0) {
      return null;
    }

    const popped = currentStack[currentStack.length - 1];
    this.framesSignal.set(currentStack.slice(0, -1));
    this.depthLimitReachedSignal.set(false);

    if (this.draftSnapshotRestorer && popped.draftSnapshot !== null && popped.draftSnapshot !== undefined) {
      this.draftSnapshotRestorer(popped.draftSnapshot);
    }

    this.restoreScrollAndFocus(popped.scrollTop, popped.activeElementId);
    return popped;
  }

  clear(): void {
    this.framesSignal.set([]);
    this.depthLimitReachedSignal.set(false);
  }

  private captureActiveElementId(): string | null {
    const active = this.document.activeElement;
    if (!(active instanceof HTMLElement) || active === this.document.body) {
      return null;
    }
    if (!active.id) {
      active.id = `mc-focus-anchor-${this.nextFocusAnchorCounter++}`;
    }
    return active.id;
  }

  private captureWorkspaceScrollTop(): number {
    const workspace = this.document.querySelector('[data-mc-workspace-scroll]');
    if (workspace instanceof HTMLElement) {
      return workspace.scrollTop;
    }
    return this.document.scrollingElement?.scrollTop ?? 0;
  }

  private restoreScrollAndFocus(scrollTop: number, activeElementId: string | null): void {
    const apply = (): void => {
      const workspace = this.document.querySelector('[data-mc-workspace-scroll]');
      if (workspace instanceof HTMLElement) {
        workspace.scrollTop = scrollTop;
      } else if (this.document.scrollingElement) {
        this.document.scrollingElement.scrollTop = scrollTop;
      }

      if (activeElementId) {
        const target = this.document.getElementById(activeElementId);
        if (target instanceof HTMLElement) {
          target.focus({ preventScroll: true });
        }
      }
    };

    apply();
    queueMicrotask(apply);
  }
}
