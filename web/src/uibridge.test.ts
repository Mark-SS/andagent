import { afterEach, describe, expect, it, vi } from "vitest";
import {
  navBack,
  navMinimize,
  uiHaptic,
  uiImmersive,
  uiKeyboard,
  uiKeepScreenOn,
  uiOrientation,
  uiScreenMetrics,
  uiSecureFlag,
  uiShare,
  uiStatus,
  uiToast,
  webReload,
  UI_PROTO_ERROR,
} from "./bridge";

type NativeBridge = NonNullable<Window["AndroidBridge"]>;

function install(partial: Partial<NativeBridge>): void {
  window.AndroidBridge = partial as NativeBridge;
}

afterEach(() => {
  delete window.AndroidBridge;
});

describe("R20 UI bridge wrappers", () => {
  it("falls back safely without a native bridge", () => {
    expect(uiToast("hi")).toEqual({ ok: false, error: "设备桥不可用" });
    expect(uiHaptic("click").ok).toBe(false);
    expect(navBack().ok).toBe(false);
    expect(navMinimize().ok).toBe(false);
    expect(uiStatus()).toBeNull();
    expect(uiScreenMetrics()).toBeNull();
    expect(webReload().ok).toBe(false);
  });

  it("forwards arguments and parses the acknowledgement", () => {
    const toast = vi.fn(() => '{"ok":true,"action":"toast"}');
    const share = vi.fn(() => '{"ok":true,"action":"share"}');
    const orientation = vi.fn(() => '{"ok":true,"action":"orientation","mode":"landscape"}');
    install({ uiToast: toast, uiShare: share, uiOrientation: orientation });

    expect(uiToast("你好", true)).toMatchObject({ ok: true, action: "toast" });
    expect(toast).toHaveBeenCalledWith("你好", true);
    expect(uiShare("正文")).toMatchObject({ ok: true, action: "share" });
    expect(share).toHaveBeenCalledWith("正文", "");
    expect(uiOrientation("landscape").ok).toBe(true);
    expect(orientation).toHaveBeenCalledWith("landscape");
  });

  it("turns ERROR strings into structured failures", () => {
    install({ uiHaptic: () => "ERROR: 不支持的震动模式 shake" });
    expect(uiHaptic("shake")).toEqual({ ok: false, error: "不支持的震动模式 shake" });
  });

  it("keeps ok=false payloads as failures", () => {
    install({ uiKeyboard: () => '{"ok":false,"error":"Activity 已结束"}' });
    expect(uiKeyboard(true)).toMatchObject({ ok: false, error: "Activity 已结束" });
  });

  it("parses status and screen metrics", () => {
    install({
      uiStatus: () => JSON.stringify({
        ok: true,
        activityAlive: true,
        keyboardVisible: false,
        immersive: true,
        keepScreenOn: true,
        secure: false,
        orientation: "portrait",
        requestedOrientation: "auto",
        webViewReady: true,
      }),
      uiScreenMetrics: () => JSON.stringify({
        ok: true,
        width: 1080,
        height: 2400,
        density: 2.75,
        orientation: "portrait",
      }),
      uiKeepScreenOn: () => '{"ok":true}',
      uiImmersive: () => '{"ok":true}',
      uiSecureFlag: () => '{"ok":true}',
    });

    expect(uiStatus()).toMatchObject({ activityAlive: true, immersive: true, orientation: "portrait" });
    expect(uiScreenMetrics()).toMatchObject({ width: 1080, height: 2400, density: 2.75 });
    expect(uiKeepScreenOn(false).ok).toBe(true);
    expect(uiImmersive(false).ok).toBe(true);
    expect(uiSecureFlag(true).ok).toBe(true);
  });

  it("rejects malformed status/metrics payloads", () => {
    install({ uiStatus: () => "{}", uiScreenMetrics: () => "not json" });
    expect(uiStatus()).toBeNull();
    expect(uiScreenMetrics()).toBeNull();
  });

  it("treats protocol violations as failures (R20 review fix)", () => {
    install({
      uiToast: () => "OK",
      uiShare: () => '{"action":"share"}',
      uiKeyboard: () => '{"ok":false}',
    });
    expect(uiToast("hi").ok).toBe(false);
    expect(uiShare("正文").ok).toBe(false);
    const kb = uiKeyboard(true);
    expect(kb.ok).toBe(false);
    expect(kb.error).toBe(UI_PROTO_ERROR);
  });
});
