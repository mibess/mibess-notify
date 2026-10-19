import {
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
  inject,
  signal,
} from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { Api, Resource, errorText } from "./api";

export function validEventPattern(value: string) {
  return (
    value.length <= 100 &&
    (/^[A-Z][A-Z0-9_]{1,99}$/.test(value) || /^[A-Z][A-Z0-9_]*_\*$/.test(value))
  );
}
export interface IntegrationDraft {
  step: number;
  applicationId: string;
  groupId: string;
  contactIds: string[];
  channelId: string;
  templateId: string;
  eventTypes: string[];
  ruleId: string;
  testContactId: string;
  notificationId: string;
  requestId: string;
  attempted: boolean;
  priority: string;
  priorityOverrides: string;
  fallbackType: string;
}
export function emptyIntegration(): IntegrationDraft {
  return {
    step: 0,
    applicationId: "",
    groupId: "",
    contactIds: [],
    channelId: "",
    templateId: "",
    eventTypes: [],
    ruleId: "",
    testContactId: "",
    notificationId: "",
    requestId: crypto.randomUUID(),
    attempted: false,
    priority: "NORMAL",
    priorityOverrides: "",
    fallbackType: "",
  };
}
/** Explicit allowlist: no API keys, credentials, message payloads or consent evidence. */
export function safeIntegrationDraft(value: any): IntegrationDraft {
  const result = emptyIntegration();
  for (const key of [
    "applicationId",
    "groupId",
    "channelId",
    "templateId",
    "ruleId",
    "testContactId",
    "notificationId",
    "requestId",
  ] as const)
    if (typeof value?.[key] === "string")
      result[key] = value[key].slice(0, 100);
  result.step = Number.isInteger(value?.step)
    ? Math.max(0, Math.min(7, value.step))
    : 0;
  result.contactIds = Array.isArray(value?.contactIds)
    ? value.contactIds.filter((id: any) => typeof id === "string").slice(0, 100)
    : [];
  result.eventTypes = Array.isArray(value?.eventTypes)
    ? value.eventTypes
        .filter(
          (type: any) => typeof type === "string" && validEventPattern(type),
        )
        .slice(0, 50)
    : [];
  result.attempted = value?.attempted === true;
  result.priority = ["LOW", "NORMAL", "HIGH", "CRITICAL"].includes(
    value?.priority,
  )
    ? value.priority
    : "NORMAL";
  result.fallbackType =
    typeof value?.fallbackType === "string" &&
    /^[A-Z][A-Z0-9_]*$/.test(value.fallbackType)
      ? value.fallbackType
      : "";
  try {
    const priorities = JSON.parse(value?.priorityOverrides || "{}");
    result.priorityOverrides = JSON.stringify(
      Object.fromEntries(
        Object.entries(priorities).filter(
          ([key, priority]) =>
            /^[A-Z][A-Z0-9_]*$/.test(key) &&
            ["LOW", "NORMAL", "HIGH", "CRITICAL"].includes(String(priority)),
        ),
      ),
    );
  } catch {
    /* Ignore malformed draft overrides. */
  }
  return result;
}
@Component({
  selector: "app-integration-wizard",
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: "./integration-wizard.html",
})
export class IntegrationWizard implements OnInit, OnDestroy {
  @Input() userId = "";
  @Output() exit = new EventEmitter<string>();
  api = inject(Api);
  steps = [
    "Aplicação",
    "Chave de API",
    "Destinatários",
    "Remetente",
    "Mensagem",
    "Eventos",
    "Teste",
    "Resumo",
  ];
  draft = emptyIntegration();
  resources = signal<Record<string, Resource[]>>({});
  catalog = signal<any[]>([]);
  keys = signal<any[]>([]);
  secret = signal("");
  error = signal("");
  busy = signal(false);
  resumed = signal(false);
  simulation = signal<any>(null);
  preview = signal<any>(null);
  delivery = signal<any>(null);
  copied = signal(false);
  savedKey = false;
  applicationName = "";
  applicationCode = "";
  contactName = "";
  contactCode = "";
  contactPhone = "";
  consent = false;
  consentSource = "";
  consentAt = "";
  groupName = "";
  groupCode = "";
  templateName = "";
  templateCode = "";
  templateBody = "*{{title}}*\n{{message}}\n{{url}}";
  templateVariables = "title, message, url";
  templateApproved = false;
  eventInput = "";
  sample =
    '{"title":"Teste da integração","message":"Sua integração está pronta.","url":"https://exemplo.com"}';
  get priority() {
    return this.draft.priority;
  }
  set priority(value: string) {
    this.draft.priority = value;
    this.persist();
  }
  get priorityOverrides() {
    return this.draft.priorityOverrides;
  }
  set priorityOverrides(value: string) {
    this.draft.priorityOverrides = value;
    this.persist();
  }
  get fallbackType() {
    return this.draft.fallbackType;
  }
  set fallbackType(value: string) {
    this.draft.fallbackType = value;
    this.persist();
  }
  private timer?: ReturnType<typeof setTimeout>;
  private stopped = false;
  get storageKey() {
    return "notify-integration:v1:" + this.userId;
  }
  get channel() {
    return this.resources()["channels"]?.find(
      (c) => c.id === this.draft.channelId,
    );
  }
  get templates() {
    return (this.resources()["templates"] || []).filter(
      (t) =>
        t.enabled && t.spec["channelConnectionId"] === this.draft.channelId,
    );
  }
  get selectedTemplate() {
    return this.templates.find((t) => t.id === this.draft.templateId);
  }
  get activeKeys() {
    return this.keys().filter((k) => k.status === "ACTIVE");
  }
  get fields(): string[] {
    return [
      ...new Set<string>(this.catalog().flatMap((e) => e.dataFields || [])),
    ].map((field) => (field.includes(".") ? "data." + field : field));
  }
  get renderedPreview() {
    const body = this.selectedTemplate?.spec["body"] || this.templateBody;
    let data: any = {};
    try {
      data = JSON.parse(this.sample);
    } catch {
      return "Informe um objeto JSON válido para a prévia.";
    }
    return body.replace(/\{\{\s*([\w.]+)\s*\}\}/g, (_: string, key: string) =>
      String(
        (key.includes(".") ? key : "data." + key)
          .split(".")
          .reduce((v: any, part: string) => v?.[part], { data }) ??
          "[ausente: " + key + "]",
      ),
    );
  }
  get variableWarnings(): string[] {
    const variables: string[] =
      this.selectedTemplate?.spec["variables"] ||
      this.templateVariables
        .split(",")
        .map((v) => v.trim())
        .filter(Boolean);
    return this.catalog().flatMap((event) =>
      variables
        .filter(
          (v) => !(event.dataFields || []).includes(v.replace(/^data\./, "")),
        )
        .map((v) => event.eventType + ": " + v),
    );
  }
  async ngOnInit() {
    try {
      const stored = localStorage.getItem(this.storageKey);
      if (stored) {
        this.draft = safeIntegrationDraft(JSON.parse(stored));
        this.resumed.set(true);
      }
    } catch {
      /* A restricted browser can still use the wizard in this session. */
    }
    await this.run(async () => {
      await this.loadResources();
      if (this.draft.applicationId) await this.loadApplication();
      if (this.draft.notificationId) await this.refreshDelivery();
      else if (this.draft.attempted) await this.recoverSend();
    });
  }
  ngOnDestroy() {
    this.stopped = true;
    clearTimeout(this.timer);
    this.secret.set("");
  }
  persist() {
    try {
      localStorage.setItem(
        this.storageKey,
        JSON.stringify(safeIntegrationDraft(this.draft)),
      );
    } catch {
      this.error.set(
        "O navegador não permite salvar o progresso. Mantenha esta aba aberta até concluir.",
      );
    }
  }
  async run(action: () => Promise<void>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set("");
    try {
      await action();
      this.persist();
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.busy.set(false);
    }
  }
  async loadResources() {
    const entries = await Promise.all(
      [
        "applications",
        "contacts",
        "groups",
        "channels",
        "templates",
        "rules",
      ].map(
        async (kind) =>
          [kind, await this.api.get<Resource[]>("admin/" + kind)] as const,
      ),
    );
    this.resources.set(Object.fromEntries(entries));
  }
  async loadApplication() {
    const id = this.draft.applicationId;
    const [keys, catalog] = await Promise.all([
      this.api.get<any[]>("admin/applications/" + id + "/keys"),
      this.api.get<any[]>("admin/applications/" + id + "/event-catalog"),
    ]);
    this.keys.set(keys);
    this.catalog.set(catalog);
    if (catalog.length)
      this.sample = JSON.stringify(catalog[0].sampleData || {}, null, 2);
  }
  applicationChanged() {
    this.resetTest();
    this.secret.set("");
    this.savedKey = false;
    this.draft.ruleId = "";
    this.draft.eventTypes = [];
    this.catalog.set([]);
    this.keys.set([]);
    this.persist();
    if (this.draft.applicationId) void this.run(() => this.loadApplication());
  }
  async create(
    kind: string,
    code: string,
    name: string,
    spec: Record<string, any>,
  ) {
    if (!name.trim() || !/^[A-Za-z][A-Za-z0-9_]{1,79}$/.test(code))
      throw new Error(
        "Informe nome e código válido: letras, números e sublinhado, de 2 a 80 caracteres.",
      );
    const resource = await this.api.post<Resource>("admin/" + kind, {
      code,
      name,
      enabled: true,
      spec,
    });
    this.resources.update((all) => ({
      ...all,
      [kind]: [...(all[kind] || []), resource],
    }));
    return resource;
  }
  createApplication() {
    void this.run(async () => {
      const app = await this.create(
        "applications",
        this.applicationCode,
        this.applicationName,
        {},
      );
      this.draft.applicationId = app.id;
      await this.loadApplication();
    });
  }
  createKey() {
    void this.run(async () => {
      const result = await this.api.post(
        "admin/applications/" + this.draft.applicationId + "/keys",
      );
      this.secret.set(result.key);
      this.savedKey = false;
      this.copied.set(false);
      this.keys.set(
        await this.api.get(
          "admin/applications/" + this.draft.applicationId + "/keys",
        ),
      );
    });
  }
  async copySecret() {
    try {
      await navigator.clipboard.writeText(this.secret());
      this.copied.set(true);
    } catch {
      this.error.set("Selecione e copie a chave exibida.");
    }
  }
  get configSnippet() {
    return (
      "MIBESS_NOTIFY_ENABLED=true\nMIBESS_NOTIFY_BASE_URL=" +
      location.origin +
      "\nMIBESS_NOTIFY_API_KEY=" +
      (this.secret() || "<sua chave guardada>")
    );
  }
  createContact() {
    void this.run(async () => {
      if (!/^\+[1-9]\d{7,14}$/.test(this.contactPhone))
        throw new Error("Use um telefone E.164, como +5516999999999.");
      if (!this.consent || !this.consentSource.trim() || !this.consentAt)
        throw new Error(
          "Registre somente consentimento já autorizado, com origem e data da evidência.",
        );
      const contact = await this.create(
        "contacts",
        this.contactCode,
        this.contactName,
        {
          phone: this.contactPhone,
          type: "INTERNAL",
          whatsappOptIn: true,
          whatsappOptInSource: this.consentSource,
          whatsappOptInAt: new Date(this.consentAt).toISOString(),
        },
      );
      this.draft.contactIds.push(contact.id);
      this.draft.testContactId ||= contact.id;
      this.contactName =
        this.contactCode =
        this.contactPhone =
        this.consentSource =
        this.consentAt =
          "";
      this.consent = false;
    });
  }
  toggleContact(id: string) {
    const set = new Set(this.draft.contactIds);
    set.has(id) ? set.delete(id) : set.add(id);
    this.draft.contactIds = [...set];
    this.persist();
  }
  createGroup() {
    void this.run(async () => {
      if (!this.draft.contactIds.length)
        throw new Error(
          "Escolha pelo menos um contato ativo para o novo grupo.",
        );
      const group = await this.create(
        "groups",
        this.groupCode,
        this.groupName,
        { members: this.draft.contactIds },
      );
      this.draft.groupId = group.id;
    });
  }
  createTemplate() {
    void this.run(async () => {
      if (!this.templateApproved)
        throw new Error(
          this.channel?.spec["provider"] === "WHATSAPP_META"
            ? "Confirme que o template já foi aprovado pela Meta."
            : "Revise e confirme a aprovação local da mensagem.",
        );
      if (!this.templateBody.trim())
        throw new Error("Preencha o corpo da mensagem.");
      const template = await this.create(
        "templates",
        this.templateCode,
        this.templateName,
        {
          channelConnectionId: this.draft.channelId,
          providerTemplateName: this.templateCode,
          language: "pt_BR",
          category: "UTILITY",
          status: "APPROVED",
          body: this.templateBody,
          variables: this.templateVariables
            .split(",")
            .map((v) => v.trim())
            .filter(Boolean),
        },
      );
      this.draft.templateId = template.id;
    });
  }
  toggleEvent(type: string) {
    const set = new Set(this.draft.eventTypes);
    set.has(type) ? set.delete(type) : set.add(type);
    if (set.size > 50) {
      this.error.set("Máximo de 50 tipos por regra.");
      return;
    }
    this.draft.eventTypes = [...set];
    this.persist();
  }
  addEvent() {
    const type = this.eventInput.trim();
    if (!validEventPattern(type)) {
      this.error.set("Use um tipo em MAIÚSCULAS ou prefixo como CRM_*.");
      return;
    }
    if (!this.draft.eventTypes.includes(type)) this.toggleEvent(type);
    this.eventInput = "";
  }
  validateStep(): string {
    const d = this.draft;
    if (
      d.step === 0 &&
      !this.resources()["applications"]?.some(
        (a) => a.id === d.applicationId && a.enabled,
      )
    )
      return "Crie ou selecione uma aplicação ativa.";
    if (
      d.step === 1 &&
      (!this.activeKeys.length || (this.secret() && !this.savedKey))
    )
      return "Gere uma chave ativa e confirme que guardou o valor exibido uma única vez.";
    if (d.step === 2) {
      const group = this.resources()["groups"]?.find(
        (g) => g.id === d.groupId && g.enabled,
      );
      if (
        !group ||
        !(group.spec["members"] || []).some((id: string) =>
          this.resources()["contacts"]?.some(
            (c) =>
              c.id === id &&
              c.enabled &&
              c.spec["whatsappOptIn"] &&
              c.spec["whatsappOptInAt"] &&
              c.spec["whatsappOptInSource"] &&
              !c.spec["whatsappOptOutAt"],
          ),
        )
      )
        return "Selecione um grupo ativo com pelo menos um contato ativo e consentimento válido.";
    }
    if (
      d.step === 3 &&
      (!this.channel?.enabled ||
        (!this.channel.spec["credentialsConfigured"] &&
          this.channel.spec["provider"] !== "FAKE"))
    )
      return "Selecione um canal ativo com credenciais configuradas.";
    if (d.step === 4 && this.selectedTemplate?.spec["status"] !== "APPROVED")
      return "Escolha ou crie um template ativo e aprovado para o canal.";
    if (d.step === 5 && !d.eventTypes.length)
      return "Selecione pelo menos um tipo de evento.";
    if (
      d.step === 6 &&
      !["SENT", "DELIVERED", "READ"].includes(
        this.delivery()?.notification?.status,
      )
    )
      return "Confirme o teste para um contato e aguarde SENT, DELIVERED ou READ antes de concluir.";
    return "";
  }
  async next() {
    const error = this.validateStep();
    if (error) {
      this.error.set(error);
      return;
    }
    await this.run(async () => {
      if (this.draft.step === 5) {
        const application = this.resources()["applications"]?.find(
          (a) => a.id === this.draft.applicationId,
        );
        const exact =
          this.draft.eventTypes.find((type) => !type.includes("*")) ||
          this.fallbackType;
        if (!exact)
          throw new Error(
            "Informe um tipo exato para compatibilidade quando usar somente prefixos.",
          );
        const configuredPriorities = this.priorityOverrides.trim()
          ? JSON.parse(this.priorityOverrides)
          : {};
        const eventPriorities = Object.fromEntries(Object.entries(configuredPriorities).filter(([type]) => this.draft.eventTypes.some((pattern) => pattern.endsWith("*") ? type.startsWith(pattern.slice(0, -1)) : type === pattern)));
        const spec = {
          applicationId: this.draft.applicationId,
          eventTypes: this.draft.eventTypes,
          eventType: exact,
          targetType: "CONTACT_GROUP",
          targetId: this.draft.groupId,
          channelConnectionId: this.draft.channelId,
          templateId: this.draft.templateId,
          conditions: [],
          priority: this.priority,
          eventPriorities,
        };
        const existing = this.resources()["rules"]?.find(
          (r) => r.id === this.draft.ruleId,
        );
        if (this.draft.attempted && !this.draft.notificationId)
          throw new Error(
            "Verifique o teste pendente antes de alterar a regra.",
          );
        const changed =
          !existing ||
          Object.entries(spec).some(
            ([key, value]) =>
              JSON.stringify(
                existing.spec[key] ?? (key === "eventPriorities" ? {} : null),
              ) !== JSON.stringify(value),
          );
        if (existing) {
          const updated = await this.api.put<Resource>(
            "admin/rules/" + existing.id,
            { code: existing.code, name: existing.name, enabled: true, spec },
          );
          this.resources.update((all) => ({
            ...all,
            rules: all["rules"].map((rule) =>
              rule.id === existing.id ? updated : rule,
            ),
          }));
        } else {
          const rule = await this.create(
            "rules",
            "INTEGRATION_" +
              crypto.randomUUID().replace(/-/g, "").slice(0, 12).toUpperCase(),
            "Avisos · " + application?.name,
            spec,
          );
          this.draft.ruleId = rule.id;
        }
        if (changed) this.resetTest();
        this.simulation.set(null);
        this.preview.set(null);
      }
      if (this.draft.step === 1) this.secret.set("");
      this.draft.step = Math.min(7, this.draft.step + 1);
      this.focusHeading();
    });
  }
  back() {
    if (this.busy()) return;
    if (this.draft.attempted && !this.draft.notificationId) {
      this.error.set(
        "Verifique o envio pendente antes de alterar a configuração.",
      );
      return;
    }
    this.secret.set("");
    this.draft.step = Math.max(0, this.draft.step - 1);
    this.error.set("");
    this.persist();
    this.focusHeading();
  }
  focusHeading() {
    setTimeout(() => document.getElementById("wizard-heading")?.focus(), 0);
  }
  simulate() {
    void this.run(async () => {
      const data = JSON.parse(this.sample);
      if (!data || typeof data !== "object" || Array.isArray(data))
        throw new Error("Informe um objeto JSON em dados de exemplo.");
      this.simulation.set(
        await this.api.post("admin/rules/" + this.draft.ruleId + "/test", {
          eventType:
            this.draft.eventTypes.find((type) => !type.includes("*")) ||
            this.fallbackType,
          data,
        }),
      );
      this.preview.set(null);
    });
  }
  reviewTest() {
    void this.run(async () => {
      if (this.draft.attempted)
        throw new Error(
          "Este teste já foi confirmado. Verifique o envio existente.",
        );
      const test = this.simulation();
      if (!test || !this.draft.testContactId)
        throw new Error("Simule a regra e selecione um contato para o teste.");
      const input = {
        requestId: this.draft.requestId,
        contactId: this.draft.testContactId,
        channelConnectionId: test.channelConnectionId,
        templateId: test.templateId,
        variables: test.variables || {},
      };
      this.preview.set({
        ...(await this.api.post("admin/notifications/manual/preview", input)),
        input,
      });
    });
  }
  confirmTest() {
    void this.run(async () => {
      const preview = this.preview();
      if (!preview) throw new Error("Revise a mensagem antes de confirmar.");
      this.draft.attempted = true;
      this.persist();
      try {
        const result = await this.api.post("admin/notifications/manual", {
          ...preview.input,
          previewHash: preview.previewHash,
        });
        this.draft.notificationId = result.notificationId;
        this.persist();
        this.preview.set(null);
        await this.refreshDelivery();
      } catch (e: any) {
        if ([400, 403, 404, 409].includes(e?.status)) {
          try {
            const result = await this.api.get(
              "admin/notifications/manual/requests/" + this.draft.requestId,
            );
            this.draft.notificationId = result.notificationId;
            this.preview.set(null);
            await this.refreshDelivery();
          } catch (lookup: any) {
            if (lookup?.status === 404) {
              this.draft.attempted = false;
              this.preview.set(null);
            }
          }
          this.persist();
        }
        throw e;
      }
    });
  }
  async recoverSend() {
    try {
      const result = await this.api.get(
        "admin/notifications/manual/requests/" + this.draft.requestId,
      );
      this.draft.notificationId = result.notificationId;
      this.persist();
      await this.refreshDelivery();
    } catch (e: any) {
      this.error.set(
        e?.status === 404
          ? "Ainda não encontramos este teste. Se a resposta foi perdida, verifique novamente antes de qualquer novo envio."
          : errorText(e),
      );
    }
  }
  async refreshDelivery() {
    if (!this.draft.notificationId || this.stopped) return;
    clearTimeout(this.timer);
    try {
      const detail = await this.api.get(
        "admin/notifications/" + this.draft.notificationId,
      );
      this.delivery.set(detail);
      if (
        !["DELIVERED", "READ", "FAILED", "CANCELLED"].includes(
          detail.notification?.status,
        )
      )
        this.timer = setTimeout(() => void this.refreshDelivery(), 3000);
    } catch (e) {
      this.error.set(errorText(e));
    }
  }
  eventPriority(type: string) {
    try {
      return JSON.parse(this.priorityOverrides || "{}")[type] || "";
    } catch {
      return "";
    }
  }
  setEventPriority(type: string, value: string) {
    let values: Record<string, string> = {};
    try {
      values = JSON.parse(this.priorityOverrides || "{}");
    } catch {
      /* reset invalid draft */
    }
    if (value) values[type] = value;
    else delete values[type];
    this.priorityOverrides = JSON.stringify(values);
  }
  selectSample(type: string) {
    const event = this.catalog().find((e) => e.eventType === type);
    if (event) this.sample = JSON.stringify(event.sampleData || {}, null, 2);
  }
  resetTest() {
    clearTimeout(this.timer);
    this.draft.notificationId = "";
    this.draft.requestId = crypto.randomUUID();
    this.draft.attempted = false;
    this.preview.set(null);
    this.simulation.set(null);
    this.delivery.set(null);
  }
  deliveryError(code: string) {
    return (
      (
        {
          MISSING_TEMPLATE_VARIABLE:
            "Uma variável da mensagem está ausente. Corrija o template ou o exemplo.",
          TEMPLATE_NOT_APPROVED: "O template precisa estar aprovado.",
          CHANNEL_DISABLED: "O canal foi desativado. Revise a configuração.",
          DELIVERY_UNKNOWN:
            "O resultado do envio é incerto. Confira no provedor antes de autorizar qualquer reenvio.",
          CONSENT_REQUIRED: "O contato precisa de consentimento válido.",
          RECIPIENT_SUPPRESSED:
            "O destinatário bloqueou o recebimento de mensagens.",
        } as Record<string, string>
      )[code] ||
      "O envio não foi concluído: " +
        code +
        ". Veja os detalhes da tentativa em Notificações."
    );
  }
  finish() {
    localStorage.removeItem(this.storageKey);
    this.exit.emit("applications");
  }
  name(kind: string, id: string) {
    return this.resources()[kind]?.find((r) => r.id === id)?.name || "—";
  }
}
