import { Component, inject, signal, computed } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import { Api, Resource, errorText } from "./api";
import { IconComponent } from "./icon";

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
  applications: [{ key: "description", label: "Descrição", type: "textarea" }],
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
    SELECT("provider", "Provider", ["WHATSAPP_META"]),
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
    REF("channelConnectionId", "Identidade remetente", "channels"),
    {
      key: "providerTemplateName",
      label: "Nome do template na Meta",
      required: true,
    },
    { key: "providerTemplateId", label: "ID do template na Meta" },
    { key: "language", label: "Idioma", placeholder: "pt_BR", required: true },
    SELECT("category", "Categoria", ["UTILITY", "MARKETING", "AUTHENTICATION"]),
    SELECT("status", "Status na Meta", [
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
      key: "eventType",
      label: "Evento de negócio",
      placeholder: "ORDER_PAID",
      required: true,
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
      key: "conditions",
      label: "Condições · todas devem corresponder",
      type: "conditions",
    },
  ],
};
@Component({
  selector: "app-root",
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: "./app.html",
})
export class AppComponent {
  api = inject(Api);
  user = signal<any>(null);
  booting = signal(true);
  loading = signal(false);
  saving = signal(false);
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
  nav = [
    { id: "dashboard", name: "Visão geral", icon: "dashboard" },
    { id: "applications", name: "Aplicações", icon: "apps" },
    { id: "events", name: "Eventos", icon: "events" },
    { id: "notifications", name: "Notificações", icon: "notifications" },
    { id: "rules", name: "Regras de envio", icon: "rules" },
    { id: "contacts", name: "Contatos", icon: "contacts" },
    { id: "groups", name: "Grupos", icon: "groups" },
    { id: "channels", name: "Canais", icon: "channels" },
    { id: "templates", name: "Templates", icon: "templates" },
    { id: "webhooks", name: "Webhooks", icon: "webhooks" },
    { id: "audit", name: "Auditoria", icon: "audit" },
    { id: "settings", name: "Configurações", icon: "settings" },
  ];
  title = computed(
    () => this.nav.find((n) => n.id === this.page())?.name || "Visão geral",
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
      if (this.nav.some((n) => n.id === p) && p !== this.page()) {
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
      if (this.nav.some((n) => n.id === p)) this.page.set(p);
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
    this.page.set(id);
    location.hash = "/" + id;
    this.index.set(0);
    this.search.set("");
    this.filterStatus = "";
    this.error.set("");
    void this.reload();
    this.mobile.set(false);
  }
  query() {
    const p = new URLSearchParams();
    if (this.filterApp) p.set("applicationId", this.filterApp);
    if (this.filterChannel) p.set("channelConnectionId", this.filterChannel);
    if (this.filterStatus) p.set("status", this.filterStatus);
    if (this.period !== "all") {
      const from = new Date();
      from.setHours(0, 0, 0, 0);
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
    return FIELDS[this.page()] || [];
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
            variables: [],
            conditions: [],
            targetType: "CONTACT_GROUP",
            priority: "NORMAL",
          },
        };
    if (Array.isArray(this.form.spec.variables))
      this.form.spec.variables = this.form.spec.variables.join(", ");
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
        for (const key of ["accessToken", "appSecret", "verifyToken"]) {
          if (body.spec[key]) credentials[key] = body.spec[key];
          delete body.spec[key];
        }
        if (Object.keys(credentials).length)
          body.spec.credentials = credentials;
      }
      if (this.page() === "rules")
        body.spec.conditions = (body.spec.conditions || []).map((c: any) => ({
          ...c,
          value: this.conditionValue(c.value, c.operator),
        }));
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
    } else this.detail.set({ record: row });
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
