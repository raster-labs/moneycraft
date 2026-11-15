import { Directive, ElementRef, HostListener, inject, input } from '@angular/core';

export function moveGridCellFocusFrom(origin: HTMLElement, direction: 1 | -1): boolean {
  const scopeRoot = origin.closest('[data-mc-nav-container]');
  if (!scopeRoot) {
    return false;
  }

  const allCells = Array.from(
    scopeRoot.querySelectorAll<HTMLElement>(
      '[mcGridCellNav]:not([disabled]), [data-mc-grid-cell="true"]:not([disabled])',
    ),
  ).filter((cell) => {
    if (cell.hasAttribute('readonly') || cell.dataset['mcSkipEnter'] === 'true') {
      return false;
    }
    return cell.offsetParent !== null || cell === origin;
  });

  const idx = allCells.indexOf(origin);
  if (idx < 0) {
    return false;
  }
  const nextCell = allCells[idx + direction];
  if (nextCell) {
    nextCell.focus();
    if (nextCell instanceof HTMLInputElement && nextCell.type === 'text') {
      nextCell.select();
    }
    return true;
  }
  return false;
}

@Directive({
  selector: '[mcGridCellNav]',
  standalone: true,
})
export class GridCellNavDirective {
  private readonly elRef = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly mcSkipReadOnlyCell = input<boolean>(false);

  @HostListener('keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (event.ctrlKey || event.altKey || event.metaKey || event.isComposing) {
      return;
    }

    if (event.key === 'Enter') {
      const target = this.elRef.nativeElement;
      if (target.tagName === 'BUTTON') {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      moveGridCellFocusFrom(target, event.shiftKey ? -1 : 1);
      return;
    }

    if (event.key === 'Backspace') {
      const target = this.elRef.nativeElement;
      if (target instanceof HTMLInputElement) {
        const atStart =
          (target.selectionStart ?? 0) === 0 && (target.selectionEnd ?? 0) === 0;
        if (atStart && target.value.length === 0) {
          event.preventDefault();
          moveGridCellFocusFrom(target, -1);
        }
      }
    }
  }
}
