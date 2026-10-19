import {
  AfterViewInit,
  Directive,
  ElementRef,
  EventEmitter,
  HostListener,
  OnDestroy,
  Output,
  inject,
} from "@angular/core";
/** Keeps keyboard interaction inside an open dialog and restores the opener. */
@Directive({ selector: "[dialogFocus]", standalone: true })
export class DialogFocus implements AfterViewInit, OnDestroy {
  private element = inject<ElementRef<HTMLElement>>(ElementRef);
  private previous = document.activeElement as HTMLElement | null;
  @Output() dialogClose = new EventEmitter<void>();
  private timer?: ReturnType<typeof setTimeout>;
  private focusable() {
    return Array.from(
      this.element.nativeElement.querySelectorAll<HTMLElement>(
        'button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),a[href],summary,[tabindex="0"]',
      ),
    ).filter((node) => !node.closest("[hidden],.hidden"));
  }
  ngAfterViewInit() {
    this.timer = setTimeout(() => this.focusable()[0]?.focus(), 0);
  }
  @HostListener("keydown", ["$event"]) keydown(event: KeyboardEvent) {
    if (event.key === "Escape") {
      event.preventDefault();
      this.dialogClose.emit();
    }
    if (event.key !== "Tab") return;
    const nodes = this.focusable(),
      first = nodes[0],
      last = nodes[nodes.length - 1];
    if (!first) {
      event.preventDefault();
      return;
    }
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
  ngOnDestroy() {
    clearTimeout(this.timer);
    if (this.previous?.isConnected) this.previous.focus();
  }
}
