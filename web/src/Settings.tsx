import { useState } from "react";
import type { Settings } from "./bridge";
import { hasBridge, persistSettings } from "./bridge";

interface Props {
  settings: Settings;
  onClose: () => void;
  onSave: (settings: Settings) => void;
}

export function SettingsPanel({ settings, onClose, onSave }: Props) {
  const [draft, setDraft] = useState<Settings>(settings);
  const [error, setError] = useState<string | null>(null);

  const save = () => {
    if (!draft.baseUrl.trim()) {
      setError("Base URL 不能为空");
      return;
    }
    if (!/^https?:\/\//i.test(draft.baseUrl.trim())) {
      setError("Base URL 需以 http:// 或 https:// 开头");
      return;
    }
    const next: Settings = {
      ...draft,
      baseUrl: draft.baseUrl.trim().replace(/\/+$/, ""),
      model: draft.model.trim() || "glm-4.5-flash",
      systemPrompt: draft.systemPrompt.trim(),
    };
    persistSettings(next);
    onSave(next);
    onClose();
  };

  return (
    <div className="settings-overlay" role="dialog" aria-modal="true">
      <div className="settings-panel">
        <header className="settings-head">
          <div>
            <h2>设置</h2>
            <p>模型通过 Kotlin 原生网络层代理，不受浏览器 CORS 限制。</p>
          </div>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="关闭">
            ✕
          </button>
        </header>

        <div className="settings-body">
          <label className="field">
            <span>Base URL</span>
            <input
              value={draft.baseUrl}
              onChange={(e) => setDraft({ ...draft, baseUrl: e.target.value })}
              placeholder="https://open.bigmodel.cn/api/paas/v4"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
            />
            <em>OpenAI 兼容端点，不要带 /chat/completions</em>
          </label>

          <label className="field">
            <span>API Key</span>
            <input
              type="password"
              value={draft.apiKey}
              onChange={(e) => setDraft({ ...draft, apiKey: e.target.value })}
              placeholder="粘贴 API Key"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
            />
            <em>仅保存在本机 SharedPreferences，不会写入 web 资产</em>
          </label>

          <label className="field">
            <span>模型 ID</span>
            <input
              value={draft.model}
              onChange={(e) => setDraft({ ...draft, model: e.target.value })}
              placeholder="glm-4.5-flash"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
            />
          </label>

          <label className="field">
            <span>System Prompt</span>
            <textarea
              rows={5}
              value={draft.systemPrompt}
              onChange={(e) => setDraft({ ...draft, systemPrompt: e.target.value })}
              placeholder="定义助手的身份与行为"
            />
          </label>

          {error && <div className="settings-error">{error}</div>}

          <div className="settings-note">
            {hasBridge() ? "运行于 Android WebView · Kotlin 桥已连接" : "浏览器预览模式 · 工具与原生网络代理不可用"}
          </div>
        </div>

        <footer className="settings-foot">
          <button type="button" className="btn-ghost" onClick={onClose}>
            取消
          </button>
          <button type="button" className="btn-primary" onClick={save}>
            保存
          </button>
        </footer>
      </div>
    </div>
  );
}
