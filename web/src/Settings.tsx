import { useEffect, useState } from "react";
import type { Settings } from "./bridge";
import {
  getDevToolsStatus,
  getShizukuStatus,
  getTermuxStatus,
  grantTermuxViaShizuku,
  hasBridge,
  persistSettings,
  requestHealthPermission,
  requestShizukuPermission,
  setDevToolsEnabled,
  type DevToolsStatus,
  type ShizukuStatus,
  widgetList, widgetSave, widgetPin, widgetRefresh, widgetDelete,
  type SavedWidget,
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
  const [termuxStatus, setTermuxStatus] = useState(() => getTermuxStatus());
  const [termuxMsg, setTermuxMsg] = useState<string | null>(null);
  const [widgets, setWidgets] = useState<SavedWidget[]>(() => widgetList());
  const [widgetId, setWidgetId] = useState("");
  const [widgetJson, setWidgetJson] = useState("");
  const [widgetMsg, setWidgetMsg] = useState("");
  const refreshWidgets = () => setWidgets(widgetList());
  const [healthMsg, setHealthMsg] = useState<string | null>(null);
  const [devtools, setDevtools] = useState<DevToolsStatus | null>(() => (hasBridge() ? getDevToolsStatus() : null));
  const [devtoolsMsg, setDevtoolsMsg] = useState<string | null>(null);

  useEffect(() => {
    if (!hasBridge()) return;
    const refresh = () => { setShizuku(getShizukuStatus()); setTermuxStatus(getTermuxStatus()); };
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
      model: draft.model.trim(),
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
              placeholder="https://api.example.com/v1"
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
            <em>优先从本机 Download/config.json 读取，不会写入代码或 web 资产</em>
          </label>

          <label className="field">
            <span>模型 ID</span>
            <input
              value={draft.model}
              onChange={(e) => setDraft({ ...draft, model: e.target.value })}
              placeholder="your-model-id"
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
            <div className="settings-card-head"><span>语音（STT 转写 / TTS 合成）</span></div>
            <label className="field">
              <span>转写模型 ID</span>
              <input
                value={draft.transcribeModel ?? ""}
                onChange={(e) => setDraft({ ...draft, transcribeModel: e.target.value })}
                placeholder="whisper-1（默认）/ glm-asr / fun-asr…"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
              />
              <em>🎤 语音输入走 baseUrl 的 /audio/transcriptions；报「模型不存在」就把服务方的模型 ID 填这里</em>
            </label>
            <label className="field">
              <span>转写端点（可选，留空跟随 Base URL）</span>
              <input
                value={draft.transcribeBaseUrl ?? ""}
                onChange={(e) => setDraft({ ...draft, transcribeBaseUrl: e.target.value })}
                placeholder="https://other-stt.example.com/v1"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
              />
            </label>
            <label className="field">
              <span>TTS 端点（可选，OpenAI 兼容 /audio/speech）</span>
              <input
                value={draft.ttsBaseUrl ?? ""}
                onChange={(e) => setDraft({ ...draft, ttsBaseUrl: e.target.value })}
                placeholder="https://tts.example.com/v1"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
              />
            </label>
            <label className="field">
              <span>TTS 模型 / 音色</span>
              <div style={{ display: "flex", gap: 8 }}>
                <input
                  value={draft.ttsModel ?? ""}
                  onChange={(e) => setDraft({ ...draft, ttsModel: e.target.value })}
                  placeholder="tts-1"
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                />
                <input
                  value={draft.ttsVoice ?? ""}
                  onChange={(e) => setDraft({ ...draft, ttsVoice: e.target.value })}
                  placeholder="alloy"
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                />
              </div>
            </label>
          </div>

          <div className="settings-card">
            <div className="settings-card-head"><span>桌面小组件</span><span>{widgets.length} 个已保存</span></div>
            <p className="settings-card-note">可在对话中描述想要的组件，也可在此粘贴并编辑 WidgetSpec v2 JSON。</p>
            {widgets.map((widget) => <div key={widget.id} className="settings-card-actions">
              <strong>{widget.name}</strong>
              <button type="button" className="btn-ghost" onClick={() => { setWidgetId(widget.id); setWidgetJson(widget.specJson); }}>编辑</button>
              <button type="button" className="btn-ghost" onClick={() => setWidgetMsg(widgetPin(widget.id))}>添加到桌面</button>
              <button type="button" className="btn-ghost" onClick={() => setWidgetMsg(widgetRefresh(widget.id))}>刷新</button>
              <button type="button" className="btn-ghost" onClick={() => { setWidgetMsg(widgetDelete(widget.id)); refreshWidgets(); if (widgetId === widget.id) { setWidgetId(""); setWidgetJson(""); } }}>删除</button>
            </div>)}
            <label className="field"><span>WidgetSpec JSON {widgetId ? "· 编辑" : "· 新建"}</span>
              <textarea rows={8} value={widgetJson} onChange={(e) => setWidgetJson(e.target.value)} spellCheck={false} placeholder={'{"schemaVersion":2,"name":"我的组件","root":{"type":"COLUMN","children":[{"type":"TEXT","text":"你好"}]}}'} />
            </label>
            <div className="settings-card-actions">
              <button type="button" className="btn-primary" onClick={() => { const result = widgetSave(widgetJson, widgetId); setWidgetMsg(result.startsWith("ERROR:") ? result : `已保存 ${result}`); if (!result.startsWith("ERROR:")) { setWidgetId(result); refreshWidgets(); } }}>保存 JSON</button>
              <button type="button" className="btn-ghost" onClick={() => { setWidgetId(""); setWidgetJson(""); }}>新建</button>
            </div>
            {widgetMsg && <p className={widgetMsg.startsWith("ERROR:") ? "settings-error" : "settings-card-note"}>{widgetMsg}</p>}
          </div>

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

          <div className="settings-card">
            <div className="settings-card-head">
              <span>DevTools 远程调试</span>
              <span className={`status-pill ${devtools?.enabled ? "status-granted" : "status-not_granted"}`}>
                {devtools?.enabled ? "已开启" : "未开启"}
              </span>
            </div>
            <p className="settings-card-note">
              开启后 agent 可用 devtools_eval / devtools_console / devtools_screenshot 检查内嵌网页；
              USB 连接电脑后可在 Chrome 的 chrome://inspect 查看这些页面。release 包默认关闭，开启为显式操作。
            </p>
            {devtools?.error && <p className="settings-error">{devtools.error}</p>}
            {devtools && devtools.pages.length > 0 && (
              <p className="settings-card-note">
                可调试页面 {devtools.pages.length} 个：{devtools.pages.slice(0, 4).map((p) => p.title || p.url).join(" · ")}
                {devtools.pages.length > 4 ? " …" : ""}
              </p>
            )}
            <div className="settings-card-actions">
              <button
                type="button"
                className="btn-primary"
                onClick={() => {
                  const ack = setDevToolsEnabled(!devtools?.enabled);
                  setDevtoolsMsg(ack.message ?? null);
                  window.setTimeout(() => setDevtools(getDevToolsStatus()), 600);
                }}
              >
                {devtools?.enabled ? "关闭调试" : "开启调试"}
              </button>
              <button type="button" className="btn-ghost" onClick={() => setDevtools(getDevToolsStatus())}>
                刷新状态
              </button>
            </div>
            {devtoolsMsg && (
              <p className={devtoolsMsg.startsWith("ERROR:") ? "settings-error" : "settings-card-note"}>{devtoolsMsg}</p>
            )}
          </div>

          <div className="settings-card">
            <div className="settings-card-head"><span>脚本后端</span><span>{termuxStatus}</span></div>
            <p className="settings-card-note">运行 termux_setup 可安装用户空间；Termux App 回退需要 RUN_COMMAND 授权及 allow-external-apps=true。</p>
            <div className="settings-card-actions">
              <button type="button" className="btn-primary" onClick={() => {
                setTermuxMsg(grantTermuxViaShizuku());
                setTermuxStatus(getTermuxStatus());
              }}>Shizuku 代授权</button>
            </div>
            {termuxMsg && <p className={termuxMsg.startsWith("ERROR:") ? "settings-error" : "settings-card-note"}>{termuxMsg}</p>}
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
