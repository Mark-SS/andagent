import { describe, it, expect } from "vitest";
import { cancelToolByCallId } from "./bridge";

// R39: cancel 钩子纯逻辑——无桥（浏览器预览/旧 APK）时必须静默无操作，
// 且未知 callId 不得触碰桥。
describe("cancelToolByCallId", () => {
  it("is a no-op without a bridge", () => {
    expect(() => cancelToolByCallId("call-1")).not.toThrow();
  });

  it("is a no-op for unknown callIds even with a bridge present", () => {
    let cancelled: string | undefined;
    (window as unknown as { AndroidBridge?: unknown }).AndroidBridge = {
      cancelTool: (id: string) => { cancelled = id; },
    };
    try {
      cancelToolByCallId("never-registered");
      expect(cancelled).toBeUndefined();
    } finally {
      delete (window as unknown as { AndroidBridge?: unknown }).AndroidBridge;
    }
  });
});
