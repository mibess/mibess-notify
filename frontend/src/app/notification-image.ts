import {
  Component,
  EventEmitter,
  Input,
  Output,
  inject,
  signal,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { Api, errorText } from "./api";

interface ImagePolicy {
  configured: boolean;
  width: number;
  height: number;
  maxBytes: number;
  contentTypes: string[];
}
@Component({
  selector: "notification-image",
  standalone: true,
  imports: [FormsModule],
  template: `
    <fieldset class="image-picker" [disabled]="disabled || busy()">
      <legend>{{ label }}</legend>
      @if (value) {
        <img
          class="notification-image"
          [src]="imageUrl(value)"
          alt="Imagem que acompanhará a notificação"
        />
        <button class="btn small" type="button" (click)="valueChange.emit('')">
          Remover seleção
        </button>
      }
      @if (policy(); as p) {
        <p class="subtle">
          JPG ou PNG · {{ p.width }} × {{ p.height }} px · até
          {{ p.maxBytes / 1000 }} KB.
        </p>
        @if (p.configured) {
          <label
            >Carregar imagem<input
              type="file"
              accept="image/jpeg,image/png"
              (change)="upload($event)"
          /></label>
          <label
            >Nome na imagem padrão<input
              [ngModel]="standardName || name"
              (ngModelChange)="standardName = $event"
              [ngModelOptions]="{ standalone: true }"
              maxlength="150"
          /></label>
          <button
            class="btn small"
            type="button"
            (click)="standard()"
            [disabled]="!(standardName || name).trim()"
          >
            Criar imagem padrão
          </button>
        } @else {
          <p class="manual-notice">
            O armazenamento de imagens no S3 ainda precisa ser configurado no
            servidor.
          </p>
        }
      }
      @if (busy()) {
        <p role="status">Preparando imagem…</p>
      }
      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }
    </fieldset>
  `,
})
export class NotificationImage {
  @Input() value = "";
  @Input() name = "";
  @Input() label = "Imagem da notificação";
  @Input() disabled = false;
  @Output() valueChange = new EventEmitter<string>();
  @Output() busyChange = new EventEmitter<boolean>();
  private api = inject(Api);
  policy = signal<ImagePolicy | null>(null);
  busy = signal(false);
  error = signal("");
  standardName = "";
  ngOnDestroy() {
    if (this.busy()) this.busyChange.emit(false);
  }
  async ngOnInit() {
    try {
      this.policy.set(await this.api.get<ImagePolicy>("admin/images/policy"));
    } catch (e) {
      this.error.set(errorText(e));
    }
  }
  imageUrl(id: string) {
    return "/api/v1/admin/images/" + encodeURIComponent(id) + "/content";
  }
  async upload(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || this.busy() || this.disabled) return;
    this.error.set("");
    const policy = this.policy();
    if (
      !policy?.contentTypes.includes(file.type) ||
      file.size > policy.maxBytes ||
      !file.size
    ) {
      this.error.set(
        "Escolha um JPG ou PNG dentro do limite de tamanho informado.",
      );
      input.value = "";
      return;
    }
    this.busy.set(true);
    this.busyChange.emit(true);
    try {
      const data = new FormData();
      data.append("file", file);
      const image = await this.api.post<{ id: string }>("admin/images", data);
      this.valueChange.emit(image.id);
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.busy.set(false);
      this.busyChange.emit(false);
      input.value = "";
    }
  }
  async standard() {
    if (
      this.busy() ||
      this.disabled ||
      !(this.standardName || this.name).trim()
    )
      return;
    this.busy.set(true);
    this.busyChange.emit(true);
    this.error.set("");
    try {
      const image = await this.api.post<{ id: string }>(
        "admin/images/standard",
        { name: (this.standardName || this.name).trim() },
      );
      this.valueChange.emit(image.id);
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.busy.set(false);
      this.busyChange.emit(false);
    }
  }
}
