import { Directive, ElementRef, HostListener, inject, input } from '@angular/core';

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
      this.moveFocus(event.shiftKey ? -1 : 1);
      return;
    }

    if (event.key === 'Backspace') {
      const target = this.elRef.nativeElement;
      if (target instanceof HTMLInputElement) {
        const atStart =
          (target.selectionStart ?? 0) === 0 && (target.selectionEnd ?? 0) === 0;
        if (atStart && target.value.length === 0) {
          event.preventDefault();
          this.moveFocus(-1);
        }
      }
    }
  }

  private moveFocus(direction: 1 | -1): void {
    const scopeRoot =
      this.elRef.nativeElement.closest('[data-mc-nav-container]') ??
      this.elRef.nativeElement.ownerDocument;
    const allCells = Array.from(
      scopeRoot.querySelectorAll<HTMLElement>('[mcGridCellNav]:not([disabled])'),
    ).filter((cell) => {
      if (cell.hasAttribute('readonly') || cell.dataset['mcSkipEnter'] === 'true') {
        return false;
      }
      return cell.offsetParent !== null || cell === this.elRef.nativeElement;
    });

    const idx = allCells.indexOf(this.elRef.nativeElement);
    if (idx < 0) {
      return;
    }
    const nextCell = allCells[idx + direction];
    if (nextCell) {
      nextCell.focus();
      if (nextCell instanceof HTMLInputElement && nextCell.type === 'text') {
        nextCell.select();
      }
    }
  }
}
