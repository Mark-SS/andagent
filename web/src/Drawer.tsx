import { useEffect, useState } from "react";
import type { SessionMeta } from "./sessions";
import { resolvedTheme, toggleTheme } from "./theme";

/**
 * Android-style navigation drawer: slides in from the left, scrim backdrop,
 * swipe-to-close. Lists conversations (new / switch / delete) and shows the
 * app version at the bottom.
 */
export function Drawer({
  open,
  sessions,
  currentId,
  version,
  onClose,
  onNew,
  onSwitch,
  onDelete,
  onExport,
}: {
  open: boolean;
  sessions: SessionMeta[];
  currentId: string;
  version: string;
  onClose: () => void;
  onNew: () => void;
  onSwitch: (id: string) => void;
  onDelete: (id: string) => void;
  onExport: (id: string) => void;
}) {
  const [visible, setVisible] = useState(open);
  const [theme, setThemeState] = useState(() => resolvedTheme());

  // Re-render the sun/moon shortcut when the theme changes.
  useEffect(() => {
    const listener = () => setThemeState(resolvedTheme());
    window.addEventListener("andagent:themechange", listener);
    return () => window.removeEventListener("andagent:themechange", listener);
  }, []);

  // Keep the element mounted during the close transition.
  useEffect(() => {
    if (open) setVisible(true);
    else {
      const t = setTimeout(() => setVisible(false), 220);
      return () => clearTimeout(t);
    }
  }, [open]);

  if (!visible) return null;

  return (
    <div className={`drawer-root ${open ? "open" : ""}`}>
      <div className="drawer-scrim" onClick={onClose} aria-hidden="true" />
      <nav className="drawer" aria-label="会话列表">
        <div className="drawer-head">
          <div>
            <strong>AndAgent</strong>
            <div className="drawer-sub">会话 · v{version}</div>
          </div>
          <button
            type="button"
            className="icon-btn"
            aria-label={theme === "dark" ? "切换到日间模式" : "切换到夜间模式"}
            title={theme === "dark" ? "日间模式" : "夜间模式"}
            onClick={toggleTheme}
          >
            {theme === "dark" ? "☀️" : "🌙"}
          </button>
        </div>

        <button type="button" className="drawer-new" onClick={onNew}>
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
          新对话
        </button>

        <div className="drawer-list">
          {sessions.length === 0 && <div className="drawer-empty">还没有历史会话</div>}
          {sessions.map((s) => (
            <div
              key={s.id}
              className={`drawer-item ${s.id === currentId ? "active" : ""}`}
              onClick={() => {
                onSwitch(s.id);
                onClose();
              }}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  onSwitch(s.id);
                  onClose();
                }
              }}
            >
              <span className="drawer-item-title">{s.title}</span>
              <span className="drawer-item-time">
                {new Date(s.updatedAt).toLocaleString("zh-CN", {
                  month: "numeric",
                  day: "numeric",
                  hour: "2-digit",
                  minute: "2-digit",
                })}
              </span>
              <button
                type="button"
                className="drawer-export"
                aria-label={`导出会话 ${s.title}`}
                onClick={(e) => { e.stopPropagation(); onExport(s.id); }}
              >导出</button>
              <button
                type="button"
                className="drawer-del"
                aria-label={`删除会话 ${s.title}`}
                onClick={(e) => {
                  e.stopPropagation();
                  onDelete(s.id);
                }}
              >
                ×
              </button>
            </div>
          ))}
        </div>

        <div className="drawer-foot">AndAgent v{version} · 设备智能助手</div>
      </nav>
    </div>
  );
}
