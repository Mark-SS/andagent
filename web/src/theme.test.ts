import { describe, it, expect, beforeEach } from "vitest";
import {
  loadThemeMode,
  setTheme,
  resolvedTheme,
  applyTheme,
  __internal,
} from "./theme";

beforeEach(() => {
  localStorage.clear();
  delete document.documentElement.dataset.theme;
  __internal.setSystemDark(true);
});

describe("theme", () => {
  it("defaults to system (resolved dark when OS is dark)", () => {
    expect(loadThemeMode()).toBe("system");
    expect(resolvedTheme()).toBe("dark");
  });

  it("persists an explicit mode and applies it to the DOM", () => {
    setTheme("light");
    expect(loadThemeMode()).toBe("light");
    expect(resolvedTheme()).toBe("light");
    expect(document.documentElement.dataset.theme).toBe("light");
    expect(document.querySelector('meta[name="color-scheme"]')?.getAttribute("content")).toBe("light");

    setTheme("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
  });

  it("system mode follows the OS switch", () => {
    setTheme("system");
    __internal.setSystemDark(false);
    expect(resolvedTheme()).toBe("light");
    expect(document.documentElement.dataset.theme).toBe("light");
    applyTheme();
    expect(document.documentElement.dataset.theme).toBe("light");
    __internal.setSystemDark(true);
    expect(resolvedTheme()).toBe("dark");
  });

  it("ignores invalid stored values", () => {
    localStorage.setItem("andagent.theme.v1", "sepia");
    expect(loadThemeMode()).toBe("system");
  });
});
