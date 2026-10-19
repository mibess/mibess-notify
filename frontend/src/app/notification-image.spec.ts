import { beforeEach, afterEach, describe, it, expect, vi } from "vitest";
import { TestBed } from "@angular/core/testing";
import { Api } from "./api";
import { NotificationImage } from "./notification-image";

describe("Notification image upload", () => {
  const api = { get: vi.fn(), post: vi.fn() };
  let picker: NotificationImage;
  const policy = {
    configured: true,
    width: 960,
    height: 320,
    maxBytes: 500000,
    contentTypes: ["image/jpeg", "image/png"],
  };
  beforeEach(() => {
    vi.resetAllMocks();
    TestBed.configureTestingModule({
      providers: [{ provide: Api, useValue: api }],
    });
    picker = TestBed.runInInjectionContext(() => new NotificationImage());
    picker.policy.set(policy);
  });
  afterEach(() => TestBed.resetTestingModule());
  it("rejects oversized files and unsupported formats before uploading", async () => {
    for (const file of [
      new File([new Uint8Array(500001)], "huge.jpg", { type: "image/jpeg" }),
      new File(["svg"], "image.svg", { type: "image/svg+xml" }),
    ]) {
      await picker.upload({
        target: { files: [file], value: "chosen" },
      } as unknown as Event);
    }
    expect(api.post).not.toHaveBeenCalled();
    expect(picker.error()).toContain("limite");
  });
  it("sends a multipart file and only adopts the server validated image", async () => {
    const emit = vi.spyOn(picker.valueChange, "emit");
    const file = new File(["test"], "image.jpg", { type: "image/jpeg" });
    api.post.mockResolvedValue({ id: "validated-image" });
    await picker.upload({
      target: { files: [file], value: "chosen" },
    } as unknown as Event);
    expect(api.post.mock.lastCall?.[0]).toBe("admin/images");
    expect(api.post.mock.lastCall?.[1]).toBeInstanceOf(FormData);
    expect(emit).toHaveBeenCalledWith("validated-image");
  });
  it("retains the selected image when server dimensions validation fails", async () => {
    picker.value = "previous-image";
    api.post.mockRejectedValue(
      new Error("A imagem deve ter exatamente 960 × 320 px."),
    );
    const emit = vi.spyOn(picker.valueChange, "emit");
    await picker.upload({
      target: {
        files: [new File(["test"], "x.jpg", { type: "image/jpeg" })],
        value: "chosen",
      },
    } as unknown as Event);
    expect(emit).not.toHaveBeenCalled();
    expect(picker.value).toBe("previous-image");
    expect(picker.error()).toContain("960 × 320");
  });
});
