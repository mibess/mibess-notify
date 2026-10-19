import { Component, inject, signal, computed } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { Api, Resource, errorText } from "./api";
import { NotificationImage } from "./notification-image";
import { IconComponent } from "./icon";
import { DialogFocus } from "./dialog-focus";
import { IntegrationWizard, validEventPattern } from "./integration-wizard";

interface Field {
  key: string;
  label: string;
  type?: string;
  required?: boolean;
  options?: string[];
  ref?: string;
  placeholder?: string;
  hint?: string;
}
export const LABELS: Record<string, string> = {
  INVALID_NOTIFICATION_IMAGE:
    "Imagem ausente, inválida ou legenda acima de 1024 caracteres",
  IMAGE_STORAGE_UNAVAILABLE: "Imagem indisponível no S3",
  NO_RULE_FOR_EVENT_TYPE: "Nenhuma regra para este tipo de evento",
  RULE_DISABLED: "A regra está desativada",
  CONDITIONS_NOT_MET: "As condições da regra não foram atendidas",
  NO_ACTIVE_RECIPIENTS: "Não há destinatários ativos",
  APPLICATION_DISABLED: "A aplicação está desativada",
  REPROCESS_REQUESTED: "Reprocessamento solicitado",
  REPROCESSED: "Evento reprocessado",
  SKIPPED: "Ignorado",
  MATCHED: "Regra correspondeu",
  EVENT_TYPE_NOT_MATCHED: "Tipo do evento diferente",
  MANUAL_CREATED: "Envio manual criado",
  PENDING: "Pendente",
  QUEUED: "Na fila",
  PROCESSING: "Processando",
  SENT: "Enviada",
  DELIVERED: "Entregue",
  READ: "Lida",
  FAILED: "Falhou",
  CANCELLED: "Cancelada",
  ACCEPTED: "Aceito",
  PROCESSED: "Processado",
  NO_MATCH: "Sem regra",
  RECEIVED: "Recebido",
  EVENT_RECEIVED: "Evento recebido",
  RULE_MATCHED: "Regra aplicada",
  RETRY_SCHEDULED: "Nova tentativa agendada",
  RETRY_REQUESTED: "Reenvio solicitado",
  DEFERRED: "Aguardando vínculo",
  UNMATCHED: "Sem vínculo",
  APPROVED: "Aprovado",
  DRAFT: "Rascunho",
  REJECTED: "Rejeitado",
  ACTIVE: "Ativa",
  REVOKED: "Revogada",
};
const SELECT = (key: string, label: string, options: string[]): Field => ({
  key,
  label,
  type: "select",
  options,
  required: true,
});
const REF = (key: string, label: string, ref: string): Field => ({
  key,
  label,
  type: "ref",
  ref,
  required: true,
});
const FIELDS: Record<string, Field[]> = {
  applications: [
    { key: "description", label: "Descrição", type: "textarea" },
    {
      key: "imageId",
      label: "Imagem padrão da aplicação / cliente",
      type: "image",
    },
  ],
  contacts: [
    { key: "externalId", label: "Identificador externo" },
    {
      key: "phone",
      label: "Telefone",
      placeholder: "+5516999999999",
      required: true,
    },
    { key: "email", label: "E-mail", type: "email" },
    SELECT("type", "Tipo de contato", ["INTERNAL", "CUSTOMER", "OTHER"]),
    {
      key: "whatsappOptIn",
      label: "Consentimento para receber mensagens",
      type: "checkbox",
    },
    {
      key: "whatsappOptInAt",
      label: "Consentimento registrado em",
      type: "datetime-local",
    },
    {
      key: "whatsappOptInSource",
      label: "Origem do consentimento",
      placeholder: "Formulário, contrato ou autorização registrada",
    },
    {
      key: "whatsappOptOutAt",
      label: "Revogação do consentimento",
      type: "datetime-local",
    },
  ],
  groups: [
    {
      key: "members",
      label: "Membros do grupo",
      type: "members",
      ref: "contacts",
    },
  ],
  channels: [
    SELECT("provider", "Provedor", ["WHATSAPP_META", "WHATSAPP_EVOLUTION"]),
    { key: "instanceName", label: "Instância Evolution", required: true },
    {
      key: "apiKey",
      label: "Chave da instância Evolution",
      type: "password",
      hint: "Deixe vazio para preservar a chave atual.",
    },
    {
      key: "webhookToken",
      label: "Token de autenticação do webhook",
      type: "password",
    },
    { key: "phoneNumberId", label: "Phone Number ID" },
    { key: "businessAccountId", label: "WhatsApp Business Account ID" },
    {
      key: "messagingAccountId",
      label: "Messaging Account ID (quando aplicável)",
    },
    {
      key: "graphApiVersion",
      label: "Versão da Graph API",
      placeholder: "v26.0",
    },
    {
      key: "accessToken",
      label: "Access token",
      type: "password",
      hint: "Deixe vazio para preservar a credencial atual.",
    },
    { key: "appSecret", label: "App secret", type: "password" },
    {
      key: "verifyToken",
      label: "Token de verificação do webhook",
      type: "password",
    },
    {
      key: "coexistence",
      label: "Conta configurada para coexistência com o Business App",
      type: "checkbox",
    },
  ],
  templates: [
    {
      key: "imageId",
      label: "Imagem deste template (opcional)",
      type: "image",
      hint: "Sem seleção, os eventos usam a imagem da aplicação. No envio manual, selecione a imagem aqui ou ao preparar a mensagem.",
    },
    {
      key: "imageDisabled",
      label: "Enviar apenas texto, sem imagem da aplicação",
      type: "checkbox",
    },
    {
      key: "headerType",
      label: "Cabeçalho aprovado na Meta",
      type: "select",
      options: ["NONE", "IMAGE"],
      hint: "Na Meta, selecione IMAGE somente se o template foi aprovado com cabeçalho de imagem. Templates NONE continuam enviando apenas texto.",
    },
    REF("channelConnectionId", "Identidade remetente", "channels"),
    {
      key: "providerTemplateName",
      label: "Nome de referência do template",
      required: true,
    },
    { key: "providerTemplateId", label: "ID do template na Meta" },
    { key: "language", label: "Idioma", placeholder: "pt_BR", required: true },
    SELECT("category", "Categoria", ["UTILITY", "MARKETING", "AUTHENTICATION"]),
    SELECT("status", "Status do template", [
      "DRAFT",
      "PENDING",
      "APPROVED",
      "REJECTED",
      "PAUSED",
    ]),
    {
      key: "body",
      label: "Corpo da mensagem",
      type: "textarea",
      placeholder: "Olá, {{customerName}}! Seu pedido {{orderNumber}}...",
    },
    {
      key: "variables",
      label: "Variáveis, na ordem dos parâmetros",
      placeholder: "customerName, orderNumber",
      hint: "Use nomes do campo data ou caminhos como recipient.name.",
    },
  ],
  rules: [
    REF("applicationId", "Aplicação", "applications"),
    {
      key: "eventTypes",
      label: "Eventos de negócio",
      type: "events",
      required: true,
    },
    {
      key: "eventType",
      label: "Tipo exato para compatibilidade",
      hint: "Para regras somente com prefixo, informe um tipo real coberto, como CRM_NOTIFY_TEST. Usado por versões anteriores.",
    },
    SELECT("targetType", "Destinatário", [
      "CONTACT_GROUP",
      "CONTACT",
      "EVENT_RECIPIENT",
    ]),
    { key: "targetId", label: "Contato ou grupo", type: "target" },
    REF("channelConnectionId", "Identidade remetente", "channels"),
    REF("templateId", "Template", "templates"),
    SELECT("priority", "Prioridade", ["LOW", "NORMAL", "HIGH", "CRITICAL"]),
    {
      key: "eventPriorities",
      label: "Prioridade por evento",
      type: "priorities",
    },
    {
      key: "conditions",
      label: "Condições · todas devem corresponder",
      type: "conditions",
    },
  ],
};
@Component({
  selector: "app-root",
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    NotificationImage,
    IconComponent,
    IntegrationWizard,
    DialogFocus,
  ],
  templateUrl: "./app.html",
})
export class AppComponent {
  api = inject(Api);
  user = signal<any>(null);
  booting = signal(true);
  loading = signal(false);
  saving = signal(false);
  imageUploading = signal(false);
  error = signal("");
  toast = signal("");
  page = signal("dashboard");
  mobile = signal(false);
  items = signal<any[]>([]);
  resources = signal<Record<string, Resource[]>>({});
  metrics = signal<any>({});
  total = signal(0);
  index = signal(0);
  search = signal("");
  detail = signal<any>(null);
  editor = signal(false);
  keyDialog = signal<any>(null);
  keys = signal<any[]>([]);
  newKey = signal("");
  email = "";
  password = "";
  filterApp = "";
  filterChannel = "";
  filterStatus = "";
  period = "today";
  editId = "";
  form: any = {};
  currentPassword = "";
  newPassword = "";
  userForm = { email: "", password: "", role: "VIEWER" };
  suppressionForm = { address: "", reason: "" };
  suppressions = signal<any[]>([]);
  users = signal<any[]>([]);
  noMatchEvents = signal<any[]>([]);
  noMatchTotal = signal(0);
  catalog = signal<any[]>([]);
  catalogApp = "";
  health = signal<any>(null);
  testingRule = signal<Resource | null>(null);
  ruleTest = signal<any>(null);
  testEventType = "";
  testData = "";
  testContactId = "";
  customEventType = "";
  nav = [
    { id: "dashboard", name: "Visão geral", icon: "dashboard" },
    { id: "events", name: "Eventos", icon: "events" },
    { id: "notifications", name: "Notificações", icon: "notifications" },
    { id: "manual", name: "Enviar notificação", icon: "send" },
    { id: "applications", name: "Integrações", icon: "apps" },
    { id: "contacts", name: "Destinatários", icon: "contacts" },
    { id: "channels", name: "Canais", icon: "channels" },
    { id: "templates", name: "Templates", icon: "templates" },
    { id: "rules", name: "Regras", icon: "rules" },
    { id: "webhooks", name: "Webhooks", icon: "webhooks" },
    { id: "audit", name: "Auditoria", icon: "audit" },
    { id: "settings", name: "Configurações", icon: "settings" },
  ];
  title = computed(() =>
    this.page() === "integration"
      ? "Nova integração"
      : this.page() === "groups"
        ? "Destinatários"
        : this.nav.find((n) => n.id === this.page())?.name || "Visão geral",
  );
  filtered = computed(() =>
    this.items().filter((r) =>
      JSON.stringify(r).toLowerCase().includes(this.search().toLowerCase()),
    ),
  );
  isResource = computed(() => Object.keys(FIELDS).includes(this.page()));
  isAdmin = computed(() => this.user()?.role === "ADMIN");
  canOperate = computed(() =>
    ["ADMIN", "OPERATOR"].includes(this.user()?.role),
  );
  descriptions: Record<string, string> = {
    dashboard: "Tudo o que acontece. Cada mensagem que chega.",
    applications: "Conecte seus sistemas por eventos de negócio.",
    integration: "Configure uma vez por sistema e acompanhe cada etapa.",
    manual:
      "Escolha um contato, escreva sua mensagem e revise antes de enviar.",
    events: "A origem de cada notificação, em um só lugar.",
    notifications: "Acompanhe cada envio, do evento à leitura.",
    rules: "Decida quem recebe, por qual canal e em qual momento.",
    contacts: "Pessoas e consentimentos, organizados com cuidado.",
    groups: "Reúna os destinatários da sua operação.",
    channels: "As identidades que conectam sua empresa às pessoas.",
    templates: "Mensagens consistentes para cada momento.",
    webhooks: "Confirmações e atualizações recebidas dos providers.",
    audit: "Um registro das ações realizadas no workspace.",
    settings: "Acesso, segurança e preferências do workspace.",
  };
  constructor() {
    void this.initialize();
    window.addEventListener("hashchange", () => {
      const p = location.hash.replace("#/", "");
      if (this.validPage(p) && p !== this.page()) {
        this.page.set(p);
        void this.reload();
      }
    });
  }
  async initialize() {
    try {
      await this.api.get("auth/csrf");
      this.user.set(await this.api.get("auth/me"));
      const p = location.hash.replace("#/", "");
      if (this.validPage(p)) this.page.set(p);
      await this.reload();
    } catch {
      this.user.set(null);
    } finally {
      this.booting.set(false);
    }
  }
  async login() {
    this.saving.set(true);
    this.error.set("");
    try {
      await this.api.get("auth/csrf");
      this.user.set(
        await this.api.post("auth/login", {
          email: this.email,
          password: this.password,
        }),
      );
      this.password = "";
      await this.api.get("auth/csrf");
      await this.reload();
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.saving.set(false);
    }
  }
  async logout() {
    try {
      await this.api.post("auth/logout");
      this.user.set(null);
      this.manualPending = null;
      this.manualImageId = "";
      this.manualPreview.set(null);
      this.manualResult.set(null);
      this.manualForm = {
        contactId: "",
        channelConnectionId: "",
        templateId: "",
        text: "",
        variables: {},
      };
      this.items.set([]);
      this.resources.set({});
      this.detail.set(null);
      this.newKey.set("");
      await this.api.get("auth/csrf");
    } catch (e) {
      this.error.set(errorText(e));
    }
  }
  navigate(id: string) {
    if (!this.validPage(id)) return;
    this.editor.set(false);
    this.detail.set(null);
    this.page.set(id);
    location.hash = "/" + id;
    this.index.set(0);
    this.search.set("");
    this.filterStatus = "";
    this.error.set("");
    void this.reload();
    this.mobile.set(false);
    setTimeout(
      () => document.querySelector<HTMLElement>("main h1")?.focus(),
      0,
    );
  }
  validPage(id: string) {
    return (
      this.nav.some((n) => n.id === id) ||
      ["groups", "integration"].includes(id)
    );
  }
  navActive(id: string) {
    return (
      this.page() === id ||
      (id === "contacts" && this.page() === "groups") ||
      (id === "applications" && this.page() === "integration")
    );
  }
  query() {
    const p = new URLSearchParams();
    if (this.filterApp) p.set("applicationId", this.filterApp);
    if (this.filterChannel) p.set("channelConnectionId", this.filterChannel);
    if (this.filterStatus) p.set("status", this.filterStatus);
    if (this.period !== "all") {
      const from = new Date();
      if (this.period === "24h") from.setTime(from.getTime() - 86400000);
      else from.setHours(0, 0, 0, 0);
      if (this.period === "7d") from.setDate(from.getDate() - 6);
      if (this.period === "30d") from.setDate(from.getDate() - 29);
      p.set("from", from.toISOString());
    } else {
      p.set("from", "1970-01-01T00:00:00Z");
    }
    p.set("page", String(this.index()));
    p.set("size", "25");
    return p.toString();
  }
  async reload() {
    if (!this.user()) return;
    this.loading.set(true);
    this.error.set("");
    try {
      const names = Object.keys(FIELDS);
      const entries = await Promise.all(
        names.map(
          async (k) =>
            [k, await this.api.get<Resource[]>("admin/" + k)] as const,
        ),
      );
      this.resources.set(Object.fromEntries(entries));
      if (this.page() === "dashboard") {
        this.metrics.set(await this.api.get("admin/dashboard?" + this.query()));
        const n = await this.api.get("admin/notifications?" + this.query());
        this.items.set(n.items);
        this.total.set(n.total);
        const params = new URLSearchParams(this.query());
        params.set("status", "NO_MATCH");
        const unmatched = await this.api.get("admin/events?" + params);
        this.noMatchEvents.set(unmatched.items || []);
        this.noMatchTotal.set(unmatched.total || 0);
      } else if (this.page() === "integration") {
        // The resumable wizard owns its step and requests.
      } else if (this.page() === "manual") {
        this.restoreManual();
        if (
          !this.manualForm.channelConnectionId &&
          this.manualChannels.length === 1
        )
          this.manualForm.channelConnectionId = this.manualChannels[0].id;
      } else if (this.isResource()) {
        this.items.set(this.resources()[this.page()] || []);
        this.total.set(this.items().length);
      } else if (this.page() === "settings") {
        this.suppressions.set(await this.api.get("admin/suppressions"));
        if (this.isAdmin()) this.users.set(await this.api.get("admin/users"));
      } else {
        const r = await this.api.get(
          "admin/" + this.page() + "?" + this.query(),
        );
        this.items.set(r.items);
        this.total.set(r.total);
      }
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.loading.set(false);
    }
  }
  refreshFilters() {
    this.index.set(0);
    void this.reload();
  }
  label(s: string) {
    return LABELS[s] || s;
  }
  name(kind: string, id: string) {
    return this.resources()[kind]?.find((r) => r.id === id)?.name || "—";
  }
  mask(phone: string) {
    return phone ? phone.slice(0, 5) + " •••• " + phone.slice(-4) : "—";
  }
  statusClass(status: string) {
    return ["DELIVERED", "READ", "PROCESSED", "APPROVED", "ACTIVE"].includes(
      status,
    )
      ? "success"
      : ["FAILED", "REJECTED", "UNMATCHED"].includes(status)
        ? "danger"
        : ["SENT", "ACCEPTED", "PROCESSING"].includes(status)
          ? "purple"
          : ["CANCELLED", "REVOKED", "NO_MATCH"].includes(status)
            ? "muted"
            : "warning";
  }
  initials(name: string) {
    return (
      name
        ?.split(/[ _-]/)
        .map((n) => n[0])
        .join("")
        .slice(0, 2)
        .toUpperCase() || "MN"
    );
  }
  get fields() {
    const fields = FIELDS[this.page()] || [];
    if (this.page() !== "channels") return fields;
    const evolution = ["instanceName", "apiKey", "webhookToken"];
    return fields.filter(
      (f) =>
        f.key === "provider" ||
        (this.form.spec.provider === "WHATSAPP_EVOLUTION"
          ? evolution.includes(f.key)
          : !evolution.includes(f.key)),
    );
  }
  choices(field: Field) {
    let ref = field.ref;
    if (field.type === "target")
      ref = this.form.spec.targetType === "CONTACT" ? "contacts" : "groups";
    let list = this.resources()[ref || ""] || [];
    if (field.key === "templateId" && this.form.spec.channelConnectionId)
      list = list.filter(
        (t) =>
          t.spec["channelConnectionId"] === this.form.spec.channelConnectionId,
      );
    return list;
  }
  openEditor(resource?: Resource) {
    this.editId = resource?.id || "";
    this.form = resource
      ? structuredClone(resource)
      : {
          code: "",
          name: "",
          enabled: false,
          spec: {
            type: "INTERNAL",
            members: [],
            provider: "WHATSAPP_META",
            graphApiVersion: "v26.0",
            language: "pt_BR",
            category: "UTILITY",
            status: "DRAFT",
            headerType: "NONE",
            variables: [],
            conditions: [],
            targetType: "CONTACT_GROUP",
            priority: "NORMAL",
          },
        };
    if (Array.isArray(this.form.spec.variables))
      this.form.spec.variables = this.form.spec.variables.join(", ");
    if (this.page() === "rules") {
      this.form.spec.eventPriorities = this.form.spec.eventPriorities || {};
      this.form.spec.eventTypes =
        this.form.spec.eventTypes ||
        (this.form.spec.eventType ? [this.form.spec.eventType] : []);
      this.catalogApp = this.form.spec.applicationId || "";
      void this.loadCatalog(this.catalogApp);
    }
    if (this.page() === "templates") {
      this.catalogApp = "";
      this.catalog.set([]);
    }
    for (const key of ["whatsappOptInAt", "whatsappOptOutAt"])
      if (this.form.spec[key]) {
        const d = new Date(this.form.spec[key]);
        d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
        this.form.spec[key] = d.toISOString().slice(0, 16);
      }
    this.editor.set(true);
    this.error.set("");
  }
  toggleMember(id: string) {
    const set = new Set<string>(this.form.spec.members || []);
    set.has(id) ? set.delete(id) : set.add(id);
    this.form.spec.members = [...set];
  }
  async save() {
    if (this.saving() || this.imageUploading()) return;
    this.saving.set(true);
    this.error.set("");
    try {
      const body = structuredClone(this.form);
      const allowed = new Set(this.fields.map((f) => f.key));
      body.spec = Object.fromEntries(
        Object.entries(body.spec).filter(([k]) => allowed.has(k)),
      );
      if (this.page() === "templates")
        body.spec.variables = String(body.spec.variables || "")
          .split(",")
          .map((s) => s.trim())
          .filter(Boolean);
      for (const key of ["whatsappOptInAt", "whatsappOptOutAt"])
        if (body.spec[key])
          body.spec[key] = new Date(body.spec[key]).toISOString();
      if (this.page() === "channels") {
        const credentials: Record<string, string> = {};
        for (const key of [
          "accessToken",
          "appSecret",
          "verifyToken",
          "apiKey",
          "webhookToken",
        ]) {
          if (body.spec[key]) credentials[key] = body.spec[key];
          delete body.spec[key];
        }
        if (Object.keys(credentials).length)
          body.spec.credentials = credentials;
      }
      if (this.page() === "rules") {
        if (!body.spec.eventTypes?.length)
          throw new Error("Selecione pelo menos um evento.");
        body.spec.eventType =
          body.spec.eventTypes.find((t: string) => !t.endsWith("*")) ||
          body.spec.eventType;
        if (!body.spec.eventType)
          throw new Error(
            "Informe um tipo exato para manter a compatibilidade da regra com versões anteriores.",
          );
        body.spec.eventPriorities = Object.fromEntries(
          Object.entries(body.spec.eventPriorities || {}).filter(
            ([type, priority]) =>
              !!priority &&
              body.spec.eventTypes.some((pattern: string) =>
                pattern.endsWith("*")
                  ? type.startsWith(pattern.slice(0, -1))
                  : type === pattern,
              ),
          ),
        );
        body.spec.conditions = (body.spec.conditions || []).map((c: any) => ({
          ...c,
          value: this.conditionValue(c.value, c.operator),
        }));
      }
      const payload = {
        code: body.code,
        name: body.name,
        enabled: body.enabled,
        spec: body.spec,
      };
      if (this.editId)
        await this.api.put("admin/" + this.page() + "/" + this.editId, payload);
      else await this.api.post("admin/" + this.page(), payload);
      this.editor.set(false);
      this.notify("Alterações salvas.");
      await this.reload();
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.saving.set(false);
    }
  }
  conditionValue(value: any, operator: string) {
    if (typeof value !== "string") return value;
    if (operator === "IN") return value.split(",").map((v) => v.trim());
    if (["GREATER_THAN", "LESS_THAN"].includes(operator)) {
      const n = Number(value);
      if (!Number.isFinite(n))
        throw new Error("Comparação numérica exige um número.");
      return n;
    }
    return value;
  }
  manualForm = {
    contactId: "",
    channelConnectionId: "",
    templateId: "",
    text: "",
    variables: {} as Record<string, string>,
  };
  manualImageId = "";
  manualMode = "text";
  manualPreview = signal<any>(null);
  manualResult = signal<any>(null);
  manualPending: any = null;
  manualRequestId = crypto.randomUUID();
  get manualChannels() {
    return (this.resources()["channels"] || []).filter(
      (c) =>
        c.enabled &&
        (c.spec["credentialsConfigured"] || c.spec["provider"] === "FAKE"),
    );
  }
  get manualChannel() {
    return this.resources()["channels"]?.find(
      (c) => c.id === this.manualForm.channelConnectionId,
    );
  }
  get manualContact() {
    return this.resources()["contacts"]?.find(
      (c) => c.id === this.manualForm.contactId,
    );
  }
  get manualUsesTemplate() {
    return (
      this.manualMode === "template" ||
      this.manualChannel?.spec["provider"] === "WHATSAPP_META"
    );
  }
  get manualTemplates() {
    return (this.resources()["templates"] || []).filter(
      (t) =>
        t.enabled &&
        t.spec["status"] === "APPROVED" &&
        t.spec["channelConnectionId"] === this.manualForm.channelConnectionId,
    );
  }
  get manualTemplate() {
    return this.manualTemplates.find(
      (t) => t.id === this.manualForm.templateId,
    );
  }
  get manualVariables(): string[] {
    return this.manualTemplate?.spec["variables"] || [];
  }
  get manualStorageKey() {
    return "notify-manual-pending:" + this.user()?.email;
  }
  restoreManual() {
    if (this.manualPending) return;
    try {
      const value = sessionStorage.getItem(this.manualStorageKey);
      if (value) {
        this.manualPending = JSON.parse(value);
        this.manualPreview.set(this.manualPending.preview);
      }
    } catch {
      /* Server idempotency remains authoritative. */
    }
  }
  manualChanged(resetTemplate = false) {
    if (this.manualPending) return;
    this.manualPreview.set(null);
    this.manualResult.set(null);
    this.manualRequestId = crypto.randomUUID();
    if (resetTemplate) {
      this.manualImageId = "";
      this.manualForm.templateId = "";
      this.manualForm.variables = {};
    }
  }
  async reviewManual() {
    if (this.saving() || this.manualPending || this.imageUploading()) return;
    this.saving.set(true);
    this.error.set("");
    try {
      const input = {
        requestId: this.manualRequestId,
        contactId: this.manualForm.contactId,
        channelConnectionId: this.manualForm.channelConnectionId,
        templateId: this.manualUsesTemplate
          ? this.manualForm.templateId || null
          : null,
        ...(this.manualImageId ? { imageId: this.manualImageId } : {}),
        text: this.manualUsesTemplate ? null : this.manualForm.text,
        variables: this.manualUsesTemplate ? this.manualForm.variables : {},
      };
      const preview = await this.api.post(
        "admin/notifications/manual/preview",
        input,
      );
      this.manualPreview.set({ ...preview, input });
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.saving.set(false);
    }
  }
  async sendManual() {
    if (this.saving() || !this.manualPreview()) return;
    this.saving.set(true);
    this.error.set("");
    try {
      if (!this.manualPending) {
        const preview = this.manualPreview();
        this.manualPending = {
          preview,
          input: { ...preview.input, previewHash: preview.previewHash },
        };
      }
      sessionStorage.setItem(
        this.manualStorageKey,
        JSON.stringify(this.manualPending),
      );
      const result = await this.api.post(
        "admin/notifications/manual",
        this.manualPending.input,
      );
      sessionStorage.removeItem(this.manualStorageKey);
      this.manualPending = null;
      this.manualPreview.set(null);
      this.manualResult.set(result);
    } catch (e: any) {
      if ([400, 403, 404, 409].includes(e?.status)) {
        sessionStorage.removeItem(this.manualStorageKey);
        this.manualPending = null;
        this.manualPreview.set(null);
      }
      this.error.set(errorText(e));
    } finally {
      this.saving.set(false);
    }
  }
  newManual() {
    if (this.manualPending) return;
    this.manualResult.set(null);
    this.manualPreview.set(null);
    this.manualImageId = "";
    this.manualForm.text = "";
    this.manualForm.variables = {};
    this.manualRequestId = crypto.randomUUID();
  }
  async showManualResult() {
    if (!this.manualResult()) return;
    try {
      this.detail.set(
        await this.api.get(
          "admin/notifications/" + this.manualResult().notificationId,
        ),
      );
    } catch (e) {
      this.error.set(errorText(e));
    }
  }
  async showDetail(row: any) {
    if (this.isResource()) {
      this.openEditor(row);
      return;
    }
    if (this.page() === "notifications" || this.page() === "dashboard") {
      try {
        this.detail.set(await this.api.get("admin/notifications/" + row.id));
      } catch (e) {
        this.error.set(errorText(e));
      }
    } else if (this.page() === "events") await this.showEvent(row.id);
    else this.detail.set({ record: row });
  }
  async showEvent(id: string) {
    try {
      this.detail.set(await this.api.get("admin/events/" + id));
    } catch (e) {
      this.error.set(errorText(e));
    }
  }
  async openNotification(id: string) {
    try {
      this.detail.set(await this.api.get("admin/notifications/" + id));
    } catch (e) {
      this.error.set(errorText(e));
    }
  }
  async reprocess() {
    if (!this.detail()?.event || this.saving()) return;
    this.saving.set(true);
    try {
      this.detail.set(
        await this.api.post(
          "admin/events/" + this.detail().event.id + "/reprocess",
        ),
      );
      this.notify(
        "Evento reprocessado com a configuração atual. Consulte o diagnóstico e a timeline.",
      );
      await this.reload();
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.saving.set(false);
    }
  }
  async openResource(kind: string, id?: string) {
    this.health.set(null);
    this.testingRule.set(null);
    this.navigate(kind);
    await this.reload();
    const resource = this.resources()[kind]?.find((r) => r.id === id);
    if (resource || (!id && this.isAdmin())) this.openEditor(resource);
  }
  async loadCatalog(applicationId: string) {
    this.catalogApp = applicationId;
    this.catalog.set([]);
    if (!applicationId) return;
    try {
      const rows = await this.api.get<any[]>(
        "admin/applications/" + applicationId + "/event-catalog",
      );
      if (this.catalogApp === applicationId) this.catalog.set(rows);
    } catch (e) {
      this.error.set(errorText(e));
    }
  }
  toggleEvent(type: string) {
    const selected = new Set<string>(this.form.spec.eventTypes || []);
    selected.has(type) ? selected.delete(type) : selected.add(type);
    this.form.spec.eventTypes = [...selected];
  }
  addEvent() {
    const type = this.customEventType.trim();
    if (!validEventPattern(type) || this.form.spec.eventTypes.length >= 50) {
      this.error.set(
        "Use um tipo em MAIÚSCULAS ou prefixo como CRM_*. Máximo de 50 tipos.",
      );
      return;
    }
    if (!this.form.spec.eventTypes.includes(type))
      this.form.spec.eventTypes.push(type);
    this.customEventType = "";
  }
  get catalogFields(): string[] {
    return [
      ...new Set<string>(this.catalog().flatMap((r) => r.dataFields || [])),
    ].map((field) => (field.includes(".") ? "data." + field : field));
  }
  get missingTemplateVariables(): string[] {
    const variables = String(this.form.spec?.variables || "")
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
  async resolveHealth(check: any) {
    const app = this.health()?.application;
    if (
      check.code === "ACTIVE_API_KEY" ||
      check.code === "NO_ACTIVE_API_KEY" ||
      check.resourceKind === "keys"
    ) {
      this.health.set(null);
      if (app && this.isAdmin()) await this.manageKeys(app);
      else this.navigate("applications");
    } else if (check.resourceKind === "events") {
      this.health.set(null);
      if (check.resourceId) await this.showEvent(check.resourceId);
      else {
        this.navigate("events");
        this.filterApp = app?.id || "";
        this.filterStatus = check.code === "NO_MATCH_24H" ? "NO_MATCH" : "";
        this.period = check.code === "NO_MATCH_24H" ? "24h" : "all";
        await this.reload();
      }
    } else await this.openResource(check.resourceKind, check.resourceId);
  }
  async showHealth(app: Resource) {
    try {
      this.health.set({
        ...(await this.api.get("admin/applications/" + app.id + "/health")),
        application: app,
      });
    } catch (e) {
      this.error.set(errorText(e));
    }
  }
  async startRuleTest(rule: Resource) {
    this.testingRule.set(rule);
    this.ruleTest.set(null);
    this.testEventType =
      (rule.spec["eventTypes"] || [rule.spec["eventType"]]).find(
        (t: string) => !t.includes("*"),
      ) || "";
    this.testData = "";
    this.testContactId = "";
    await this.loadCatalog(rule.spec["applicationId"]);
  }
  async simulateRule() {
    if (!this.testingRule() || this.saving()) return;
    this.saving.set(true);
    try {
      const data = this.testData.trim() ? JSON.parse(this.testData) : undefined;
      if (data && (typeof data !== "object" || Array.isArray(data)))
        throw new Error("O exemplo deve ser um objeto JSON.");
      this.ruleTest.set(
        await this.api.post("admin/rules/" + this.testingRule()!.id + "/test", {
          eventType: this.testEventType || undefined,
          data,
        }),
      );
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.saving.set(false);
    }
  }
  async reviewRuleSend() {
    if (!this.testContactId || !this.ruleTest()) return;
    this.restoreManual();
    if (this.manualPending) {
      this.error.set(
        "Existe um envio pendente de confirmação. Verifique-o em Enviar notificação antes de iniciar outro teste.",
      );
      return;
    }
    const test = this.ruleTest();
    this.manualMode = "template";
    this.manualForm = {
      contactId: this.testContactId,
      channelConnectionId: test.channelConnectionId,
      templateId: test.templateId,
      text: "",
      variables: test.variables || {},
    };
    this.manualChanged();
    this.testingRule.set(null);
    this.navigate("manual");
    await this.reviewManual();
  }
  async retry() {
    if (!this.detail()) return;
    this.saving.set(true);
    try {
      await this.api.post(
        "admin/notifications/" + this.detail().notification.id + "/retry",
      );
      this.notify("Reenvio agendado. O histórico foi preservado.");
      this.detail.set(null);
      await this.reload();
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.saving.set(false);
    }
  }
  async manageKeys(app: Resource) {
    this.keyDialog.set(app);
    this.newKey.set("");
    this.keys.set(await this.api.get("admin/applications/" + app.id + "/keys"));
  }
  async createKey(rotate = false) {
    this.saving.set(true);
    try {
      const r = await this.api.post(
        "admin/applications/" + this.keyDialog().id + "/keys?rotate=" + rotate,
      );
      this.newKey.set(r.key);
      this.keys.set(
        await this.api.get(
          "admin/applications/" + this.keyDialog().id + "/keys",
        ),
      );
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.saving.set(false);
    }
  }
  async revokeKey(id: string) {
    try {
      await this.api.delete(
        "admin/applications/" + this.keyDialog().id + "/keys/" + id,
      );
      this.keys.set(
        await this.api.get(
          "admin/applications/" + this.keyDialog().id + "/keys",
        ),
      );
      this.notify("Chave revogada.");
    } catch (e) {
      this.error.set(errorText(e));
    }
  }
  async copyKey() {
    try {
      await navigator.clipboard.writeText(this.newKey());
      this.notify("Chave copiada.");
    } catch {
      this.notify("Selecione e copie a chave exibida.");
    }
  }
  async changePassword() {
    this.saving.set(true);
    try {
      await this.api.post("auth/password", {
        currentPassword: this.currentPassword,
        newPassword: this.newPassword,
      });
      this.currentPassword = "";
      this.newPassword = "";
      this.notify("Senha alterada.");
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.saving.set(false);
    }
  }
  async createUser() {
    this.saving.set(true);
    try {
      await this.api.post("admin/users", this.userForm);
      this.userForm = { email: "", password: "", role: "VIEWER" };
      this.notify("Usuário criado.");
      await this.reload();
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.saving.set(false);
    }
  }
  async suppress() {
    this.saving.set(true);
    try {
      await this.api.post("admin/suppressions", this.suppressionForm);
      this.suppressionForm = { address: "", reason: "" };
      this.notify("Destinatário adicionado à lista de supressão.");
      await this.reload();
    } catch (e) {
      this.error.set(errorText(e));
    } finally {
      this.saving.set(false);
    }
  }
  changeIndex(delta: number) {
    this.index.update((n) => n + delta);
    void this.reload();
  }
  notify(text: string) {
    this.toast.set(text);
    setTimeout(() => this.toast.set(""), 5000);
  }
  bars() {
    return this.metrics().daily || [];
  }
  barHeight(value: number) {
    const max = Math.max(...this.bars().map((d: any) => Number(d.total)), 1);
    return Math.max(5, (100 * value) / max);
  }
}
