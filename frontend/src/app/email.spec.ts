import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TestBed } from "@angular/core/testing";
import { AppComponent } from "./app";
import { IntegrationWizard } from "./integration-wizard";
import { Api, Resource } from "./api";
const resource = (id: string, spec: Record<string, any>): Resource => ({
  id,
  code: id.toUpperCase(),
  name: id,
  enabled: true,
  spec,
  createdAt: "2026-11-17T00:00:00Z",
});
const email = resource("email", {
  provider: "EMAIL_SMTP",
  credentialsConfigured: true,
  fromEmail: "contato@artgian.com.br",
});
const whatsapp = resource("whatsapp", {
  provider: "WHATSAPP_EVOLUTION",
  credentialsConfigured: true,
});
const contact = resource("buyer", {
  email: "buyer@example.com",
  whatsappOptIn: false,
});
describe("Email channel administration and manual delivery", () => {
  const api = { get: vi.fn(), post: vi.fn(), put: vi.fn() };
  let app: AppComponent;
  let wizard: IntegrationWizard | undefined;
  beforeEach(async () => {
    sessionStorage.clear();
    localStorage.clear();
    window.location.hash = "#/channels";
    api.get.mockReset();
    api.post.mockReset();
    api.put.mockReset();
    api.get.mockImplementation(async (path: string) =>
      path === "auth/me"
        ? { email: "admin@example.com", role: "ADMIN" }
        : path.includes("?")
          ? { items: [], total: 0 }
          : [],
    );
    api.post.mockResolvedValue({});
    api.put.mockResolvedValue({});
    TestBed.configureTestingModule({
      providers: [{ provide: Api, useValue: api }],
    });
    app = TestBed.runInInjectionContext(() => new AppComponent());
    await app.initialize();
    app.resources.set({
      channels: [email, whatsapp],
      contacts: [contact],
      groups: [resource("group", { members: ["buyer"] })],
    });
    vi.spyOn(app, "reload").mockResolvedValue();
  });
  afterEach(() => {
    wizard?.ngOnDestroy();
    wizard = undefined;
    TestBed.resetTestingModule();
    sessionStorage.clear();
    localStorage.clear();
  });
  it("saves SMTP credentials separately and strips WhatsApp configuration", async () => {
    app.page.set("channels");
    app.openEditor();
    app.form.spec = {
      provider: "EMAIL_SMTP",
      smtpHost: "smtp.zoho.com",
      smtpPort: "465",
      smtpSecurity: "TLS",
      fromEmail: "contato@artgian.com.br",
      username: "smtp-user",
      password: "test-password",
      instanceName: "must-not-copy",
      accessToken: "must-not-copy",
    };
    await app.save();
    const spec = api.post.mock.lastCall?.[1].spec;
    expect(spec).toMatchObject({
      provider: "EMAIL_SMTP",
      smtpPort: 465,
      credentials: { username: "smtp-user", password: "test-password" },
    });
    for (const key of ["password", "username", "instanceName", "accessToken"])
      expect(spec).not.toHaveProperty(key);
  });
  it("preserves stored SMTP credentials when editing without new secrets", async () => {
    app.page.set("channels");
    app.openEditor({
      ...email,
      spec: {
        ...email.spec,
        smtpHost: "smtp.zoho.com",
        smtpPort: 465,
        smtpSecurity: "TLS",
      },
    });
    await app.save();
    expect(api.put.mock.lastCall?.[1].spec).not.toHaveProperty("credentials");
    expect(app.error()).toBe("");
  });
  it("requires email subject and text while omitting images and Meta fields", async () => {
    app.page.set("templates");
    app.openEditor();
    app.form.spec = {
      channelConnectionId: "email",
      subject: "",
      body: "Pedido confirmado",
      variables: "orderNumber",
      html: "<p>{{orderNumber}}</p>",
      imageId: "old-image",
      providerTemplateName: "old-name",
    };
    expect(app.fields.map((f) => f.key)).toContain("subject");
    expect(app.fields.map((f) => f.key)).not.toContain("imageId");
    await app.save();
    expect(api.post).not.toHaveBeenCalled();
    app.form.spec.subject = "Pedido {{orderNumber}}";
    await app.save();
    expect(api.post.mock.lastCall?.[1].spec).toMatchObject({
      subject: "Pedido {{orderNumber}}",
      html: "<p>{{orderNumber}}</p>",
      variables: ["orderNumber"],
    });
    expect(api.post.mock.lastCall?.[1].spec).not.toHaveProperty(
      "providerTemplateName",
    );
    app.form.spec.channelConnectionId = "whatsapp";
    expect(app.fields.map((f) => f.key)).not.toContain("subject");
    expect(app.fields.map((f) => f.key)).toContain("imageId");
  });
  it("accepts email-only contacts without creating WhatsApp consent", async () => {
    app.page.set("contacts");
    app.openEditor();
    app.form.spec = {
      email: "buyer@example.com",
      type: "CUSTOMER",
      whatsappOptIn: false,
    };
    await app.save();
    expect(api.post.mock.lastCall?.[1].spec).toEqual({
      email: "buyer@example.com",
      type: "CUSTOMER",
      whatsappOptIn: false,
    });
  });
  it("reviews email subject and HTML without images or freeform content on templates", async () => {
    app.manualForm = {
      contactId: "buyer",
      channelConnectionId: "email",
      templateId: "",
      text: "Pedido confirmado",
      variables: {},
    };
    app.manualSubject = "Seu pedido";
    app.manualHtml = "<p>Pedido confirmado</p>";
    app.manualImageId = "stale-image";
    api.post.mockResolvedValue({
      previewHash: "hash",
      recipientAddress: "buyer@example.com",
      fromAddress: "contato@artgian.com.br",
      subject: "Seu pedido",
      text: "Pedido confirmado",
    });
    await app.reviewManual();
    expect(api.post.mock.lastCall?.[1]).toMatchObject({
      contactId: "buyer",
      subject: "Seu pedido",
      html: "<p>Pedido confirmado</p>",
    });
    expect(api.post.mock.lastCall?.[1]).not.toHaveProperty("imageId");
    expect(app.manualPreview().fromAddress).toBe("contato@artgian.com.br");
    app.manualMode = "template";
    app.manualForm.templateId = "email-template";
    app.manualChanged();
    await app.reviewManual();
    expect(api.post.mock.lastCall?.[1]).not.toHaveProperty("subject");
    expect(api.post.mock.lastCall?.[1]).not.toHaveProperty("html");
  });
  it("keeps WhatsApp free of email content and distinguishes SMTP acceptance", async () => {
    app.manualForm.channelConnectionId = "whatsapp";
    app.manualSubject = "stale";
    app.manualHtml = "<p>stale</p>";
    await app.reviewManual();
    expect(api.post.mock.lastCall?.[1]).not.toHaveProperty("subject");
    expect(api.post.mock.lastCall?.[1]).not.toHaveProperty("html");
    expect(app.notificationStatus("SENT", "email")).toBe("Aceita pelo SMTP");
    expect(app.notificationStatus("SENT", "whatsapp")).toBe("Enviada");
    expect(app.mask("buyer@example.com")).toBe("bu•••@example.com");
  });
  it("validates wizard contacts against the selected channel", () => {
    wizard = TestBed.runInInjectionContext(() => new IntegrationWizard());
    wizard.resources.set(app.resources());
    wizard.draft.groupId = "group";
    wizard.draft.step = 2;
    expect(wizard.validateStep()).toBe("");
    wizard.draft.step = 3;
    wizard.draft.channelId = "email";
    expect(wizard.validateStep()).toBe("");
    wizard.draft.channelId = "whatsapp";
    expect(wizard.validateStep()).toContain("consentimento");
  });
  it("renders email sender and subject without WhatsApp image or consent prompts", async () => {
    const fixture = TestBed.createComponent(AppComponent);
    const view = fixture.componentInstance;
    await view.initialize();
    view.resources.set(app.resources());
    view.page.set("manual");
    view.manualForm.contactId = "buyer";
    view.manualForm.channelConnectionId = "email";
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector("#manual-subject")).not.toBeNull();
    expect(root.querySelector("notification-image")).toBeNull();
    expect(root.querySelector("#manual-channel")?.textContent).toContain(
      "contato@artgian.com.br",
    );
    expect(root.querySelector(".manual-consent")?.textContent).toContain(
      "buyer@example.com",
    );
    expect(root.querySelector(".manual-consent")?.textContent).not.toContain(
      "precisa de consentimento",
    );
    fixture.destroy();
  });
  it("keeps historical channel and sender after the channel configuration changes", async () => {
    const fixture = TestBed.createComponent(AppComponent);
    const view = fixture.componentInstance;
    await view.initialize();
    view.resources.set({
      channels: [
        {
          ...email,
          spec: {
            provider: "WHATSAPP_EVOLUTION",
            fromEmail: "changed@example.com",
          },
        },
      ],
      contacts: [],
      applications: [],
      templates: [],
    });
    view.page.set("notifications");
    const notification = {
      id: "sent-email",
      channel: "EMAIL",
      channel_connection_id: "email",
      status: "SENT",
      source: "MANUAL",
      recipient_name: "Buyer",
      recipient_address: "buyer@example.com",
      created_at: "2026-11-17T00:00:00Z",
    };
    view.items.set([notification]);
    view.detail.set({
      notification,
      fromAddress: "original@artgian.com.br",
      subject: "Pedido",
      message: "Confirmado",
      timeline: [],
      attempts: [],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector(".channel-label")?.textContent).toContain(
      "E-mail",
    );
    const summary = root.querySelector(".notification-summary")?.textContent;
    expect(summary).toContain("original@artgian.com.br");
    expect(summary).not.toContain("changed@example.com");
    expect(summary).toContain("Aceita pelo SMTP");
    expect(view.notificationStatus("SENT", "email", "WHATSAPP")).toBe(
      "Enviada",
    );
    fixture.destroy();
  });
  it("stops polling after SMTP acceptance without inventing delivery confirmation", async () => {
    wizard = TestBed.runInInjectionContext(() => new IntegrationWizard());
    wizard.resources.set(app.resources());
    wizard.draft.channelId = "whatsapp";
    wizard.draft.notificationId = "mail";
    api.get.mockResolvedValue({
      notification: { status: "SENT", channel: "EMAIL" },
      timeline: [],
    });
    const timer = vi.spyOn(globalThis, "setTimeout");
    await wizard.refreshDelivery();
    expect(wizard.delivery().notification.status).toBe("SENT");
    expect(timer).not.toHaveBeenCalled();
    timer.mockRestore();
  });
});
