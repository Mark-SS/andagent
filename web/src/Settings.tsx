import { useEffect, useState } from "react";
import type { Settings } from "./bridge";
import {
  getShizukuStatus,
  hasBridge,
  persistSettings,
  requestHealthPermission,
  requestShizukuPermission,
  type ShizukuStatus,
} from "./bridge";

interface Props {
  settings: Settings;
  onClose: () => void;
  onSave: (settings: Settings) => void;
}

export function SettingsPanel({ settings, onClose, onSave }: Props) {
  const [draft, setDraft] = useState<Settings>(settings);
  const [error, setError] = useState<string | null>(null);
  const [shizuku, setShizuku] = useState<ShizukuStatus>(() => getShizukuStatus());
  const [shizukuMsg, setShizukuMsg] = useState<string | null>(null);
  const [healthMsg, setHealthMsg] = useState<string | null>(null);

  useEffect(() => {
    if (!hasBridge()) return;
    const refresh = () => setShizuku(getShizukuStatus());
    refresh();
    const timer = window.setInterval(refresh, 4000);
    return () => window.clearInterval(timer);
  }, []);

  const askShizuku = () => {
    const ack = requestShizukuPermission();
    setShizukuMsg(ack.message ?? null);
    window.setTimeout(() => setShizuku(getShizukuStatus()), 800);
  };

  const askHealth = () => {
    const ack = requestHealthPermission();
    setHealthMsg(ack.message ?? null);
  };

  const shizukuLabel =
    shizuku.status === "granted"
      ? "已授权"
      : shizuku.status === "not_granted"
        ? "未授权"
        : "不可用";

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

          <div className="settings-card">
            <div className="settings-card-head">
              <span>Shizuku 提权</span>
              <span className={`status-pill status-${shizuku.status}`}>{shizukuLabel}</span>
            </div>
            <p className="settings-card-note">
              {shizuku.note ?? "录屏等功能需要 Shizuku 以 shell 身份运行。"}
              {shizuku.managerVersion ? ` · v${shizuku.managerVersion}` : ""}
            </p>
            {shizukuMsg && <p className="settings-card-note">{shizukuMsg}</p>}
            {shizuku.lastRequestError && (
              <p className="settings-error">授权失败：{shizuku.lastRequestError}</p>
            )}
            <div className="settings-card-actions">
              <button type="button" className="btn-primary" onClick={askShizuku}>
                请求授权
              </button>
              <a
                className="btn-ghost"
                href="market://details?id=moe.shizuku.privileged.api"
              >
                去激活 / 安装 Shizuku
              </a>
              <button type="button" className="btn-ghost" onClick={() => setShizuku(getShizukuStatus())}>
                刷新状态
              </button>
            </div>
            <details className="settings-details">
              <summary>激活步骤（Android 11+ 无线调试）</summary>
              <ol>
                <li>安装并打开 Shizuku（包名 moe.shizuku.privileged.api）。</li>
                <li>在系统开发者选项中开启「无线调试」。</li>
                <li>回到 Shizuku，选择「通过无线调试启动」，按提示完成配对。</li>
                <li>回到本页点击「请求授权」并在 Shizuku 弹窗中允许。</li>
                <li>Android 10 及以下需要电脑通过 ADB 启动 Shizuku 服务。</li>
              </ol>
            </details>
          </div>

          <div className="settings-card">
            <div className="settings-card-head">
              <span>健康数据（步数）</span>
            </div>
            <p className="settings-card-note">
              Android 14 起 Health Connect 内置；Android 13 及以下需先安装 Health Connect 应用。
              授权后可使用 steps_today / steps_history。
            </p>
            {healthMsg && <p className="settings-card-note">{healthMsg}</p>}
            <div className="settings-card-actions">
              <button type="button" className="btn-ghost" onClick={askHealth}>
                授权步数读取
              </button>
            </div>
          </div>

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
