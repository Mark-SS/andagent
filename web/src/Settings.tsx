import { useEffect, useState } from "react";
import type { Settings } from "./bridge";
import {
  getDevToolsStatus,
  getProjectionStatus,
  getShizukuStatus,
  getTermuxStatus,
  grantTermuxViaShizuku,
  hasBridge,
  persistSettings,
  requestHealthPermission,
  requestProjectionConsent,
  requestShizukuPermission,
  setDevToolsEnabled,
  stopProjection,
  type DevToolsStatus,
  type ProjectionStatus,
  type ShizukuStatus,
  cronList, cronToggle, cronDelete, cronPauseAll, cronExactAllowed, cronOpenExactSettings,
  type CronTask, skillManage, memoryCall, type MemoryEntry,
  getAppVersion,
} from "./bridge";
import noticesText from "../../THIRD-PARTY-NOTICES.md?raw";
import { setLanguageMode, useI18n, type LanguageMode } from "./i18n";
import { loadThemeMode, setTheme, type ThemeMode } from "./theme";

const THEME_OPTIONS: { value: ThemeMode; label: string }[] = [
  { value: "system", label: "跟随系统" },
  { value: "light", label: "日间" },
  { value: "dark", label: "夜间" },
];

interface Props {
  settings: Settings;
  onClose: () => void;
  onSave: (settings: Settings) => void;
}

export function SettingsPanel({ settings, onClose, onSave }: Props) {
  const { t, mode } = useI18n();
  const [showNotices, setShowNotices] = useState(false);
  const [draft, setDraft] = useState<Settings>(settings);
  const [error, setError] = useState<string | null>(null);
  const [themeMode, setThemeMode] = useState<ThemeMode>(() => loadThemeMode());
  const [shizuku, setShizuku] = useState<ShizukuStatus>(() => getShizukuStatus());
  const [shizukuMsg, setShizukuMsg] = useState<string | null>(null);
  const [termuxStatus, setTermuxStatus] = useState(() => getTermuxStatus());
  const [termuxMsg, setTermuxMsg] = useState<string | null>(null);
  const [cronTasks, setCronTasks] = useState<CronTask[]>(() => cronList());
  const [cronMsg, setCronMsg] = useState("");
  const [cronDetail, setCronDetail] = useState("");
  const [exactAllowed, setExactAllowed] = useState(() => cronExactAllowed());
  const refreshCron = () => { setCronTasks(cronList()); setExactAllowed(cronExactAllowed()); };
  const [healthMsg, setHealthMsg] = useState<string | null>(null);
  const [projection, setProjection] = useState<ProjectionStatus>(() => getProjectionStatus());
  const [projectionMsg, setProjectionMsg] = useState<string | null>(null);
  const [devtools, setDevtools] = useState<DevToolsStatus | null>(() => (hasBridge() ? getDevToolsStatus() : null));
  const [devtoolsMsg, setDevtoolsMsg] = useState<string | null>(null);
  const [skills, setSkills] = useState<Array<{name:string; description:string; enabled:boolean; toolCount:number; source:string}>>([]);
  const [skillSource, setSkillSource] = useState("");
  const [skillQuery, setSkillQuery] = useState("");
  const [skillIndex, setSkillIndex] = useState("");
  const [skillMsg, setSkillMsg] = useState("");
  const refreshSkills = () => { try { setSkills(JSON.parse(skillManage("list"))); } catch { setSkills([]); } };
  useEffect(refreshSkills, []);
  const [memories, setMemories] = useState<MemoryEntry[]>([]);
  const [memoryEdits, setMemoryEdits] = useState<Record<string, string>>({});
  const [memoryMsg, setMemoryMsg] = useState("");
  const refreshMemories = () => {
    try { setMemories(memoryCall<MemoryEntry[]>("memory_get_all")); }
    catch (e) { setMemoryMsg(String(e)); }
  };
  useEffect(refreshMemories, []);
  const saveMemory = (id: string) => {
    try { memoryCall("memory_update", { id, content: memoryEdits[id] ?? memories.find(m => m.id === id)?.content ?? "" }); setMemoryMsg(t("saved")); refreshMemories(); }
    catch (e) { setMemoryMsg(String(e)); }
  };
  const deleteMemory = (id: string) => {
    try { memoryCall("memory_delete", { id }); refreshMemories(); }
    catch (e) { setMemoryMsg(String(e)); }
  };


  useEffect(() => {
    if (!hasBridge()) return;
    const refresh = () => { setShizuku(getShizukuStatus()); setTermuxStatus(getTermuxStatus()); setProjection(getProjectionStatus()); };
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

  const askProjection = () => {
    const ack = requestProjectionConsent();
    setProjectionMsg(ack.message ?? null);
    window.setTimeout(() => setProjection(getProjectionStatus()), 1500);
  };

  const shizukuLabel =
    shizuku.status === "granted"
      ? "已授权"
      : shizuku.status === "not_granted"
        ? "未授权"
        : "不可用";

  const projectionLabel =
    projection.channel === "projection"
      ? projection.recording ? "录屏中" : "已授权（MediaProjection）"
      : projection.channel === "shizuku"
        ? "走 Shizuku"
        : "未配置";

  const save = () => {
    if (!draft.baseUrl.trim()) {
      setError(t("baseRequired"));
      return;
    }
    if (!/^https?:\/\//i.test(draft.baseUrl.trim())) {
      setError(t("baseInvalid"));
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
            <h2>{t("settings")}</h2>
            <p>{t("settingsNote")}</p>
          </div>
          <button type="button" className="icon-btn" onClick={onClose} aria-label={t("close")}>
            ✕
          </button>
        </header>

        <div className="settings-body">
          <div className="settings-card">
            <div className="settings-card-head"><span>{t("about")}</span></div>
            <p className="settings-card-note">{t("version")}: {getAppVersion()} · {t("built")}: {__BUILD_TIME__}</p>
            <p className="settings-card-note">{t("license")}: Apache-2.0</p>
            <div className="settings-card-actions">
              <a className="btn-ghost" href="https://github.com/congduan/andagent" target="_blank" rel="noreferrer">{t("repository")}</a>
              <a className="btn-ghost" href="https://github.com/congduan/andagent/stargazers" target="_blank" rel="noreferrer">{t("star")}</a>
              <button type="button" className="btn-ghost" onClick={() => setShowNotices(v => !v)}>{t("notices")}</button>
            </div>
            {showNotices && <div className="settings-notices"><pre>{noticesText || t("noticesUnavailable")}</pre></div>}
          </div>
          <div className="settings-card">
            <div className="settings-card-head"><span>{t("language")}</span></div>
            <div className="theme-segmented" role="radiogroup" aria-label={t("language")}>
              {(["system", "zh", "en"] as LanguageMode[]).map(value => <button key={value} type="button" role="radio" aria-checked={mode === value} className={mode === value ? "active" : ""} onClick={() => setLanguageMode(value)}>{value === "system" ? t("system") : value === "zh" ? t("chinese") : t("english")}</button>)}
            </div>
          </div>
          <div className="settings-card">
            <div className="settings-card-head"><span>{t("appearance")}</span></div>
            <div className="theme-segmented" role="radiogroup" aria-label={t("appearance")}>
              {THEME_OPTIONS.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  role="radio"
                  aria-checked={themeMode === option.value}
                  className={themeMode === option.value ? "active" : ""}
                  onClick={() => { setTheme(option.value); setThemeMode(option.value); }}
                >
                  {t(option.value)}
                </button>
              ))}
            </div>
            <p className="settings-card-note">{t("themeNote")}</p>
          </div>

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
            <em>{t("baseUrlHint")}</em>
          </label>

          <label className="field">
            <span>API Key</span>
            <input
              type="password"
              value={draft.apiKey}
              onChange={(e) => setDraft({ ...draft, apiKey: e.target.value })}
              placeholder={t("apiKeyPlaceholder")}
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
            />
            <em>{t("apiKeyHint")}</em>
          </label>

          <label className="field">
            <span>{t("modelId")}</span>
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
              placeholder={t("systemPromptPlaceholder")}
            />
          </label>

          <div className="settings-card">
            <div className="settings-card-head"><span>{t("memory")}</span></div>
            <p className="settings-card-note">{t("memoryNote")}</p>
            <strong>{t("coreMemory")} ({memories.filter(m => m.core).length}/10)</strong>
            {memories.filter(m => m.core).map(m => (
              <div className="memory-entry" key={m.id}>
                <small>{m.category}</small>
                <textarea rows={2} maxLength={500} value={memoryEdits[m.id] ?? m.content}
                  onChange={e => setMemoryEdits({ ...memoryEdits, [m.id]: e.target.value })} />
                <button type="button" onClick={() => saveMemory(m.id)}>{t("save")}</button>
                <button type="button" onClick={() => deleteMemory(m.id)}>删除</button>
              </div>
            ))}
            <strong>{t("archivedMemory")} ({memories.filter(m => !m.core).length}/200)</strong>
            {memories.filter(m => !m.core).map(m => (
              <div className="memory-entry" key={m.id}>
                <span>{m.content}</span>
                <button type="button" onClick={() => deleteMemory(m.id)}>删除</button>
              </div>
            ))}
            <button type="button" onClick={() => {
              if (!window.confirm(t("clearConfirm"))) return;
              try { memoryCall("memory_delete_all", { confirm: true }); refreshMemories(); setMemoryMsg(t("cleared")); }
              catch (e) { setMemoryMsg(String(e)); }
            }}>{t("clearMemory")}</button>
            {memoryMsg && <p role="status">{memoryMsg}</p>}
          </div>

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
            <div className="settings-card-head"><span>{t("skills")}</span><span>{skills.length} 个</span></div>
            {skills.map((skill) => <div key={skill.name} className="settings-card-actions">
              <span title={skill.source}>{skill.name} · {skill.description} · {skill.toolCount} 工具</span>
              <label><input type="checkbox" checked={skill.enabled} onChange={(e) => { setSkillMsg(skillManage("enable", skill.name, String(e.target.checked))); refreshSkills(); }} />启用</label>
              <button type="button" className="btn-ghost" onClick={() => { if (window.confirm(`删除技能「${skill.name}」？`)) { setSkillMsg(skillManage("delete", skill.name, "true")); refreshSkills(); } }}>删除</button>
            </div>)}
            <div className="settings-card-actions"><input value={skillSource} onChange={(e) => setSkillSource(e.target.value)} placeholder="技能 URL 或本地路径" /><button type="button" className="btn-ghost" onClick={() => { setSkillMsg(skillManage("install", "", skillSource)); refreshSkills(); }}>安装</button></div>
            <div className="settings-card-actions"><input value={skillQuery} onChange={(e) => setSkillQuery(e.target.value)} placeholder="搜索技能" /><button type="button" className="btn-ghost" onClick={() => setSkillMsg(skillManage("search", "", skillQuery))}>搜索</button><button type="button" className="btn-ghost" onClick={() => { setSkillMsg(skillManage("reload")); refreshSkills(); }}>扫描刷新</button></div>
            <div className="settings-card-actions"><input value={skillIndex} onChange={(e) => setSkillIndex(e.target.value)} placeholder="自定义 JSON 索引 URL（可选）" /><button type="button" className="btn-ghost" onClick={() => setSkillMsg(skillManage("index", "", skillIndex))}>保存索引</button></div>
            {skillMsg && <p className="settings-card-note">{skillMsg.slice(0, 1200)}</p>}
          </div>

          <div className="settings-card">
            <div className="settings-card-head"><span>{t("cron")}</span><span>{cronTasks.length} 个任务</span></div>
            <p className="settings-card-note">可在对话中创建定时任务。极端省电模式下，系统可能延迟几分钟触发。</p>
            {!exactAllowed && hasBridge() && <div className="settings-card-actions">
              <span>精确闹钟权限未授予</span>
              <button type="button" className="btn-ghost" onClick={() => { setCronMsg(cronOpenExactSettings()); window.setTimeout(refreshCron, 1000); }}>打开「闹钟和提醒」设置</button>
            </div>}
            {cronTasks.map((task) => <div key={task.id}>
              <div className="settings-card-actions">
                <button type="button" className="btn-ghost" onClick={() => setCronDetail(cronDetail === task.id ? "" : task.id)}>{task.name} · {task.schedule} · {task.kind}</button>
                <label><input type="checkbox" checked={task.enabled} onChange={(e) => { setCronMsg(cronToggle(task.id, e.target.checked)); refreshCron(); }} /> 启用</label>
                <button type="button" className="btn-ghost" onClick={() => { if (window.confirm(`删除定时任务「${task.name}」？`)) { setCronMsg(cronDelete(task.id)); refreshCron(); } }}>删除</button>
              </div>
              {cronDetail === task.id && <p className="settings-card-note">上次：{task.lastRun ? new Date(task.lastRun).toLocaleString() : "尚未运行"} · 下次：{task.nextRun ? new Date(task.nextRun).toLocaleString() : "已暂停"}<br />上次结果：{task.lastResult || "无"}</p>}
            </div>)}
            <div className="settings-card-actions">
              <button type="button" className="btn-ghost" onClick={() => { setCronMsg(cronPauseAll()); refreshCron(); }}>{t("pauseAll")}</button>
              <button type="button" className="btn-ghost" onClick={refreshCron}>刷新</button>
            </div>
            {cronMsg && <p className={cronMsg.startsWith("ERROR:") ? "settings-error" : "settings-card-note"}>{cronMsg}</p>}
          </div>

          <div className="settings-card">
            <div className="settings-card-head">
              <span>{t("shizuku")}</span>
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
              <span>屏幕捕获（截屏 / 录屏）</span>
              <span className={`status-pill ${projection.channel === "projection" ? "status-granted" : "status-not_granted"}`}>
                {projectionLabel}
              </span>
            </div>
            <p className="settings-card-note">
              截屏 / 录屏优先走 MediaProjection（无需 Shizuku，系统弹窗授权一次即可，之后全自动）；
              未授权时回退 Shizuku（scrcpy / screenrecord）。授权后如需彻底关闭，点「停止」。
            </p>
            {projection.error && <p className="settings-error">{projection.error}</p>}
            {projectionMsg && <p className="settings-card-note">{projectionMsg}</p>}
            <div className="settings-card-actions">
              <button type="button" className="btn-primary" onClick={askProjection}>
                授权屏幕捕获
              </button>
              <button
                type="button"
                className="btn-ghost"
                onClick={() => { setProjectionMsg(stopProjection()); window.setTimeout(() => setProjection(getProjectionStatus()), 600); }}
              >
                停止
              </button>
              <button type="button" className="btn-ghost" onClick={() => setProjection(getProjectionStatus())}>
                刷新状态
              </button>
            </div>
          </div>

          <div className="settings-card">
            <div className="settings-card-head">
              <span>{t("health")}</span>
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
              <span>{t("devtools")}</span>
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
            <div className="settings-card-head"><span>{t("scriptBackend")}</span><span>{termuxStatus}</span></div>
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
            {hasBridge() ? t("bridgeConnected") : t("browserPreview")}
          </div>
        </div>

        <footer className="settings-foot">
          <button type="button" className="btn-ghost" onClick={onClose}>
            {t("cancel")}
          </button>
          <button type="button" className="btn-primary" onClick={save}>
            {t("save")}
          </button>
        </footer>
      </div>
    </div>
  );
}
