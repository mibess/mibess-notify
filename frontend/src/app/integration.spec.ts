import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TestBed } from "@angular/core/testing";
import { HttpErrorResponse } from "@angular/common/http";
import { Api, Resource } from "./api";
import { AppComponent } from "./app";
import {
  IntegrationWizard,
  safeIntegrationDraft,
  validEventPattern,
} from "./integration-wizard";
const resource = (id: string, spec: Record<string, any> = {}): Resource => ({
  id,
  code: id.toUpperCase(),
  name: id,
  enabled: true,
  spec,
  createdAt: "2026-10-19T10:00:00Z",
});
const fixtures = () => ({
  applications: [resource("app")],
  contacts: [
    resource("contact", {
      phone: "+5516999999999",
      whatsappOptIn: true,
      whatsappOptInAt: "2026-10-19T10:00:00Z",
      whatsappOptInSource: "Autorização",
    }),
  ],
  groups: [resource("group", { members: ["contact"] })],
  channels: [resource("channel", { provider: "FAKE" })],
  templates: [
    resource("template", {
      channelConnectionId: "channel",
      status: "APPROVED",
      body: "{{title}}",
      variables: ["title"],
    }),
  ],
  rules: [],
});
describe("Guided integration and navigation", () => {
  const api = { get: vi.fn(), post: vi.fn(), put: vi.fn() };
  let wizard: IntegrationWizard | undefined;
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    window.location.hash = "#/dashboard";
    api.get.mockReset();
    api.post.mockReset();
    api.put.mockReset();
    const all: Record<string, Resource[]> = fixtures();
    api.get.mockImplementation(async (path: string) => {
      if (path === "auth/me")
        return {
          email: "admin@example.test",
          role: "ADMIN",
          workspace: "Teste",
        };
      if (path.endsWith("/keys")) return [{ id: "key", status: "ACTIVE" }];
      if (path.endsWith("/event-catalog"))
        return [
          {
            eventType: "CRM_NOTIFY_TEST",
            dataFields: ["title"],
            sampleData: { title: "Exemplo recebido" },
          },
        ];
      if (path === "admin/notifications/notification")
        return {
          notification: { id: "notification", status: "SENT" },
          timeline: [{ id: 1, status: "SENT" }],
        };
      if (path.startsWith("admin/notifications/manual/requests/"))
        return { notificationId: "notification", status: "SENT" };
      if (path.includes("?")) return { items: [], total: 0 };
      return all[path.replace("admin/", "")] || {};
    });
    api.post.mockImplementation(async (path: string, body: any) => {
      if (path.endsWith("/keys")) return { key: "secret-visible-once" };
      if (path === "admin/rules")
        return { ...resource("rule", body.spec), ...body };
      if (path.endsWith("/test"))
        return {
          matched: true,
          recipients: [
            {
              contactId: "contact",
              name: "Contato",
              address: "+5516999999999",
            },
          ],
          renderedMessage: "Exemplo recebido",
          variables: { title: "Exemplo recebido" },
          channelConnectionId: "channel",
          templateId: "template",
          problems: [],
        };
      if (path.endsWith("/preview"))
        return {
          previewHash: "reviewed",
          recipientName: "Contato",
          recipientAddress: "+5516999999999",
          text: "Exemplo recebido",
        };
      if (path === "admin/notifications/manual")
        return { notificationId: "notification", status: "PENDING" };
      return {};
    });
    api.put.mockImplementation(async (_path: string, body: any) => ({
      ...resource("rule"),
      ...body,
    }));
    TestBed.configureTestingModule({
      providers: [{ provide: Api, useValue: api }],
    });
  });
  afterEach(() => {
    wizard?.ngOnDestroy();
    wizard = undefined;
    TestBed.resetTestingModule();
    localStorage.clear();
    sessionStorage.clear();
  });
  async function setupWizard() {
    wizard = TestBed.runInInjectionContext(() => new IntegrationWizard());
    wizard.userId = "admin@example.test";
    await wizard.ngOnInit();
    return wizard;
  }
  it("renders grouped navigation and preserves legacy groups links and recipient tabs", async () => {
    const fixture = TestBed.createComponent(AppComponent),
      app = fixture.componentInstance;
    await app.initialize();
    fixture.detectChanges();
    const menu: HTMLElement = fixture.nativeElement.querySelector(
      'nav[aria-label="Menu principal"]',
    );
    for (const label of [
      "OPERAÇÃO",
      "CONFIGURAÇÃO",
      "SISTEMA",
      "Integrações",
      "Destinatários",
    ])
      expect(menu.textContent).toContain(label);
    app.navigate("groups");
    await app.reload();
    fixture.detectChanges();
    expect(window.location.hash).toBe("#/groups");
    expect(app.navActive("contacts")).toBe(true);
    const tabs = fixture.nativeElement.querySelector(
      'nav[aria-label="Destinatários"]',
    );
    expect(tabs.textContent).toContain("Contatos");
    expect(tabs.textContent).toContain("Grupos");
    app.navigate("integration");
    expect(app.navActive("applications")).toBe(true);
    fixture.destroy();
  });
  it("validates every step and completes eight steps only after explicit one-contact preview and send", async () => {
    const w = await setupWizard();
    await w.next();
    expect(w.draft.step).toBe(0);
    expect(w.error()).toContain("aplicação");
    w.draft.applicationId = "app";
    await w.loadApplication();
    await w.next();
    expect(w.draft.step).toBe(1);
    await w.next();
    expect(w.draft.step).toBe(2);
    await w.next();
    expect(w.draft.step).toBe(2);
    expect(w.error()).toContain("grupo");
    w.draft.groupId = "group";
    await w.next();
    w.draft.channelId = "channel";
    await w.next();
    expect(w.draft.step).toBe(4);
    w.draft.templateId = "template";
    await w.next();
    w.draft.eventTypes = ["CRM_NOTIFY_TEST"];
    await w.next();
    expect(w.draft.step).toBe(6);
    await w.next();
    expect(w.draft.step).toBe(6);
    w.simulate();
    await vi.waitFor(() => expect(w.busy()).toBe(false));
    w.draft.testContactId = "contact";
    w.reviewTest();
    await vi.waitFor(() => expect(w.preview()).not.toBeNull());
    expect(
      api.post.mock.calls.filter(
        ([path]) => path === "admin/notifications/manual",
      ),
    ).toHaveLength(0);
    w.confirmTest();
    await vi.waitFor(() =>
      expect(w.delivery()?.notification.status).toBe("SENT"),
    );
    expect(
      api.post.mock.calls.filter(
        ([path]) => path === "admin/notifications/manual",
      ),
    ).toHaveLength(1);
    expect(
      api.post.mock.calls.find(
        ([path]) => path === "admin/notifications/manual",
      )?.[1],
    ).toMatchObject({ contactId: "contact", previewHash: "reviewed" });
    await w.next();
    expect(w.draft.step).toBe(7);
    expect(localStorage.getItem(w.storageKey)).not.toContain(
      "Exemplo recebido",
    );
  });
  it("displays a key once, requires acknowledgment and resumes without secrets or personal payloads", async () => {
    const w = await setupWizard();
    w.draft.applicationId = "app";
    w.draft.step = 1;
    w.createKey();
    await vi.waitFor(() => expect(w.secret()).toBe("secret-visible-once"));
    await w.next();
    expect(w.draft.step).toBe(1);
    w.savedKey = true;
    await w.next();
    expect(w.secret()).toBe("");
    expect(localStorage.getItem(w.storageKey)).not.toContain(
      "secret-visible-once",
    );
    const safe = safeIntegrationDraft({
      ...w.draft,
      apiKey: "secret",
      text: "personal",
      variables: { name: "personal" },
      priority: "HIGH",
      fallbackType: "CRM_NOTIFY_TEST",
      priorityOverrides: '{"CRM_SYNC_FAILED":"HIGH"}',
    });
    expect(JSON.stringify(safe)).not.toMatch(/secret|personal/);
    expect(safe.priority).toBe("HIGH");
    expect(safe.fallbackType).toBe("CRM_NOTIFY_TEST");
    w.ngOnDestroy();
    const resumed = await setupWizard();
    expect(resumed.draft.step).toBe(2);
    expect(resumed.secret()).toBe("");
  });
  it("recovers an ambiguous send after refresh using only its request id", async () => {
    const w = await setupWizard();
    w.draft.attempted = true;
    w.draft.step = 6;
    w.persist();
    const id = w.draft.requestId;
    w.ngOnDestroy();
    const resumed = await setupWizard();
    expect(api.get).toHaveBeenCalledWith(
      "admin/notifications/manual/requests/" + id,
    );
    expect(resumed.draft.notificationId).toBe("notification");
    expect(api.post).not.toHaveBeenCalled();
  });
  it("invalidates an earlier successful test when the saved rule changes", async () => {
    const w = await setupWizard();
    const spec = {
      applicationId: "app",
      eventTypes: ["CRM_NOTIFY_TEST"],
      eventType: "CRM_NOTIFY_TEST",
      targetType: "CONTACT_GROUP",
      targetId: "group",
      channelConnectionId: "channel",
      templateId: "template",
      conditions: [],
      priority: "NORMAL",
      eventPriorities: {},
    };
    w.resources.update((all) => ({ ...all, rules: [resource("rule", spec)] }));
    Object.assign(w.draft, {
      step: 5,
      applicationId: "app",
      groupId: "group",
      channelId: "channel",
      templateId: "template",
      ruleId: "rule",
      eventTypes: ["CRM_NOTIFY_TEST", "CRM_SYNC_FAILED"],
      attempted: true,
      notificationId: "notification",
    });
    w.delivery.set({ notification: { status: "SENT" } });
    await w.next();
    expect(w.draft.step).toBe(6);
    expect(w.draft.attempted).toBe(false);
    expect(w.delivery()).toBeNull();
    expect(w.draft.notificationId).toBe("");
  });
  it("allows another preview after a definite stale-preview rejection and confirmed absence", async () => {
    const w = await setupWizard();
    w.preview.set({
      previewHash: "stale",
      input: { requestId: w.draft.requestId },
    });
    api.post.mockRejectedValueOnce(new HttpErrorResponse({ status: 409 }));
    api.get.mockRejectedValueOnce(new HttpErrorResponse({ status: 404 }));
    w.confirmTest();
    await vi.waitFor(() => expect(w.busy()).toBe(false));
    expect(w.draft.attempted).toBe(false);
    expect(w.preview()).toBeNull();
  });
  it("accepts exact types and prefix wildcards with the backend limits", () => {
    expect(validEventPattern("CRM_*")).toBe(true);
    expect(validEventPattern("CRM_NOTIFY_TEST")).toBe(true);
    for (const type of ["*", "CRM*", "X", "crm_test", "A".repeat(101)])
      expect(validEventPattern(type)).toBe(false);
  });
  it("offers nested variables as payload paths and previews those exact paths", async () => {
    const w = await setupWizard();
    w.catalog.set([{ eventType: "CRM_TEST", dataFields: ["customer.name"] }]);
    w.templateBody = "{{data.customer.name}} / {{customer.name}}";
    w.sample = '{"customer":{"name":"Pessoa"}}';
    expect(w.fields).toEqual(["data.customer.name"]);
    expect(w.renderedPreview).toBe("Pessoa / [ausente: customer.name]");
  });
});
