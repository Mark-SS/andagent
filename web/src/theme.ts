/**
 * Day/night theme switching (R39).
 *
 * Three modes: "system" | "light" | "dark". The resolved theme is written to
 * document.documentElement.dataset.theme so CSS picks it via
 * `:root[data-theme="light"]`, persisted in localStorage, and reported to the
 * Kotlin side (WebView background / status bar) through the bridge so the
 * native chrome matches and startup doesn't flash the wrong color.
 */

export type ThemeMode = "system" | "light" | "dark";

const THEME_KEY = "andagent.theme.v1";
const MODES: ThemeMode[] = ["system", "light", "dark"];

let systemDark = window.matchMedia?.("(prefers-color-scheme: dark)").matches ?? true;

export function loadThemeMode(): ThemeMode {
  try {
    const raw = localStorage.getItem(THEME_KEY);
    return MODES.includes(raw as ThemeMode) ? (raw as ThemeMode) : "system";
  } catch {
    return "system";
  }
}

export function resolvedTheme(mode: ThemeMode = loadThemeMode()): "light" | "dark" {
  if (mode === "system") return systemDark ? "dark" : "light";
  return mode;
}

/** Report the resolved theme to Kotlin (WebView background, status bar). */
function reportToNative(isDark: boolean): void {
  try {
    window.AndroidBridge?.reportTheme?.(isDark);
  } catch {
    /* bridge not connected (browser preview) */
  }
}

/** Apply the current mode to the DOM and notify listeners. */
export function applyTheme(mode: ThemeMode = loadThemeMode()): "light" | "dark" {
  const resolved = resolvedTheme(mode);
  document.documentElement.dataset.theme = resolved;
  let meta = document.querySelector<HTMLMetaElement>('meta[name="color-scheme"]');
  if (!meta) {
    meta = document.createElement("meta");
    meta.name = "color-scheme";
    document.head.appendChild(meta);
  }
  meta.content = resolved;
  reportToNative(resolved === "dark");
  return resolved;
}

export function setTheme(mode: ThemeMode): void {
  try {
    localStorage.setItem(THEME_KEY, mode);
  } catch {
    /* storage unavailable */
  }
  applyTheme(mode);
  window.dispatchEvent(new CustomEvent("andagent:themechange", { detail: resolvedTheme(mode) }));
}

/** Toggle used by the drawer shortcut: light <-> dark (explicit mode). */
export function toggleTheme(): void {
  setTheme(resolvedTheme() === "dark" ? "light" : "dark");
}

/** Follow OS switches while mode === "system". Call once at startup. */
export function watchSystemTheme(): void {
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener?.("change", (e) => {
    const changed = systemDark !== e.matches;
    systemDark = e.matches;
    if (changed && loadThemeMode() === "system") {
      applyTheme("system");
      window.dispatchEvent(new CustomEvent("andagent:themechange", { detail: resolvedTheme("system") }));
    }
  });
}

/** Test hook: force the cached system-dark value. */
export const __internal = {
  setSystemDark(dark: boolean) {
    systemDark = dark;
    if (loadThemeMode() === "system") applyTheme("system");
  },
};
