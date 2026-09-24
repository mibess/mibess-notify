import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TestBed } from "@angular/core/testing";
import { AppComponent } from "./app";
import { Api } from "./api";
import { HttpErrorResponse } from "@angular/common/http";

describe("Manual notification confirmation", () => {
  let component: AppComponent;
  const api = { get: vi.fn(), post: vi.fn() };
  beforeEach(async () => {
    sessionStorage.clear();
    window.location.hash = "#/manual";
    api.get.mockReset();
    api.post.mockReset();
    api.get.mockImplementation(async (path: string) =>
      path === "auth/me"
        ? { email: "operator@example.test", role: "OPERATOR" }
        : path.startsWith("admin/")
          ? []
          : {},
    );
    TestBed.configureTestingModule({
      providers: [{ provide: Api, useValue: api }],
    });
    component = TestBed.runInInjectionContext(() => new AppComponent());
    await component.initialize();
    component.manualForm = {
      contactId: "contact",
      channelConnectionId: "channel",
      templateId: "",
      text: "Uma mensagem",
      variables: {},
    };
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    sessionStorage.clear();
  });
  it("previews first and sends only when explicitly confirmed", async () => {
    api.post.mockResolvedValue({
      previewHash: "hash",
      recipientName: "Contato",
      recipientAddress: "+5516999999999",
      text: "Uma mensagem",
    });
    await component.reviewManual();
    expect(api.post.mock.calls.map((c) => c[0])).toEqual([
      "admin/notifications/manual/preview",
    ]);
    api.post.mockResolvedValue({
      notificationId: "notification",
      status: "PENDING",
    });
    await component.sendManual();
    expect(api.post.mock.lastCall?.[0]).toBe("admin/notifications/manual");
    expect(api.post.mock.lastCall?.[1].previewHash).toBe("hash");
    expect(component.manualResult().notificationId).toBe("notification");
  });
  it("reuses the same request after a lost response, including after refresh", async () => {
    api.post.mockResolvedValueOnce({
      previewHash: "hash",
      text: "Uma mensagem",
    });
    await component.reviewManual();
    api.post.mockRejectedValueOnce(new HttpErrorResponse({ status: 0 }));
    await component.sendManual();
    const request = structuredClone(api.post.mock.lastCall?.[1]);
    expect(component.manualPending).not.toBeNull();
    component.manualPending = null;
    component.manualPreview.set(null);
    component.restoreManual();
    api.post.mockResolvedValueOnce({
      notificationId: "notification",
      duplicate: true,
      status: "SENT",
    });
    await component.sendManual();
    expect(api.post.mock.lastCall?.[1]).toEqual(request);
    expect(sessionStorage.getItem(component.manualStorageKey)).toBeNull();
  });
  it("blocks repeated confirmation while the request is pending", async () => {
    api.post.mockResolvedValueOnce({
      previewHash: "hash",
      text: "Uma mensagem",
    });
    await component.reviewManual();
    let resolve!: (value: unknown) => void;
    api.post.mockImplementationOnce(() => new Promise((r) => (resolve = r)));
    const first = component.sendManual();
    await component.sendManual();
    expect(
      api.post.mock.calls.filter((c) => c[0] === "admin/notifications/manual"),
    ).toHaveLength(1);
    resolve({ notificationId: "one" });
    await first;
  });
});
