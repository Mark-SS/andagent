import { afterEach, describe, expect, it } from "vitest";
import { DEFAULT_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT_EN, loadSettings } from "./bridge";
import { setLanguageMode } from "./i18n";

afterEach(() => { localStorage.clear(); delete window.AndroidBridge; });

describe("system prompt locale", () => {
  it("selects the current language for an unset prompt", () => {
    setLanguageMode("zh");
    expect(loadSettings().systemPrompt).toBe(DEFAULT_SYSTEM_PROMPT);
    setLanguageMode("en");
    expect(loadSettings().systemPrompt).toBe(DEFAULT_SYSTEM_PROMPT_EN);
  });

  it("preserves a saved browser prompt", () => {
    localStorage.setItem("andagent.settings.dev.v1", JSON.stringify({ systemPrompt: "My custom instructions" }));
    setLanguageMode("en");
    expect(loadSettings().systemPrompt).toBe("My custom instructions");
  });
});
