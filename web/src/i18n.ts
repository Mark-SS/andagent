import { useSyncExternalStore } from "react";

export type LanguageMode = "system" | "zh" | "en";
const KEY = "andagent.language";
const zh = {
  settings: "设置", close: "关闭", cancel: "取消", save: "保存", appearance: "外观",
  system: "跟随系统", light: "日间", dark: "夜间", language: "语言 Language",
  chinese: "中文", english: "English", about: "关于 AndAgent", version: "版本",
  built: "构建时间", repository: "GitHub 仓库", license: "开源协议", notices: "开源组件协议",
  star: "喜欢 AndAgent？在 GitHub 点 Star", themeNote: "夜间为默认；跟随系统时自动随系统深浅色切换。",
  settingsNote: "模型通过 Kotlin 原生网络层代理，不受浏览器 CORS 限制。",
  baseUrlHint: "OpenAI 兼容端点，不要带 /chat/completions", apiKeyPlaceholder: "粘贴 API Key",
  apiKeyHint: "优先从本机 Download/config.json 读取，不会写入代码或 web 资产",
  modelId: "模型 ID", systemPromptPlaceholder: "定义助手的身份与行为",
  baseRequired: "Base URL 不能为空", baseInvalid: "Base URL 需以 http:// 或 https:// 开头",
  memory: "记忆", memoryNote: "核心记忆每次对话常驻；归档记忆按当前问题检索。",
  coreMemory: "核心记忆", archivedMemory: "归档记忆", delete: "删除", saved: "已保存",
  cleared: "已清空", clearMemory: "清空全部记忆", clearConfirm: "清空全部记忆？此操作无法撤销。",
  skills: "技能", enabled: "启用", install: "安装", search: "搜索", refresh: "刷新",
  cron: "定时任务", pauseAll: "全部暂停", shizuku: "Shizuku 提权", granted: "已授权",
  notGranted: "未授权", unavailable: "不可用", requestAccess: "请求授权", refreshStatus: "刷新状态",
  health: "健康数据（步数）", devtools: "DevTools 远程调试", scriptBackend: "脚本后端",
  browserPreview: "浏览器预览模式 · 工具与原生网络代理不可用",
  bridgeConnected: "运行于 Android WebView · Kotlin 桥已连接",
  sessions: "会话列表", newChat: "新对话", send: "发送", stop: "停止生成",
  emptyTitle: "今天想让我做什么？", emptyBody: "我可以读取设备状态、分析存储与相册，并把结果整理成结论。",
  missingKey: "还没有配置 API Key，模型请求会失败。", goSettings: "去设置", requestFailed: "请求失败",
  inputPlaceholder: "给 AndAgent 发消息…", inputNoKey: "先在设置中填入 API Key…",
  inputRecording: "录音中…再次点击停止", inputTranscribing: "转写中…",
  composerHint: "Enter 发送 · Shift+Enter 换行 · AndAgent 可能会调用设备工具，请注意核对结果",
  thinking: "思考过程", thinkingNow: "思考中…", copied: "已复制", copy: "复制",
  renderFailed: "这条消息渲染失败。", aborted: "已中断（消耗未回报）",
  buildUnknown: "未知", noticesUnavailable: "组件声明暂时无法读取。",
} as const;
type Key = keyof typeof zh;
const en: Record<Key, string> = {
  settings: "Settings", close: "Close", cancel: "Cancel", save: "Save", appearance: "Appearance",
  system: "System", light: "Light", dark: "Dark", language: "Language 语言",
  chinese: "中文", english: "English", about: "About AndAgent", version: "Version",
  built: "Build time", repository: "GitHub repository", license: "Open-source license", notices: "Third-party notices",
  star: "Enjoy AndAgent? Star it on GitHub", themeNote: "Dark is the default; System follows your device theme.",
  settingsNote: "Model requests use the Kotlin network proxy, so WebView CORS does not apply.",
  baseUrlHint: "OpenAI-compatible endpoint; omit /chat/completions", apiKeyPlaceholder: "Paste API Key",
  apiKeyHint: "Loaded from local Download/config.json first; never bundled into source or web assets",
  modelId: "Model ID", systemPromptPlaceholder: "Define the assistant's identity and behavior",
  baseRequired: "Base URL is required", baseInvalid: "Base URL must start with http:// or https://",
  memory: "Memory", memoryNote: "Core memories stay in every conversation; archived memories are retrieved when relevant.",
  coreMemory: "Core memory", archivedMemory: "Archived memory", delete: "Delete", saved: "Saved",
  cleared: "Cleared", clearMemory: "Clear all memories", clearConfirm: "Clear all memories? This cannot be undone.",
  skills: "Skills", enabled: "Enabled", install: "Install", search: "Search", refresh: "Refresh",
  cron: "Scheduled tasks", pauseAll: "Pause all", shizuku: "Shizuku access", granted: "Granted",
  notGranted: "Not granted", unavailable: "Unavailable", requestAccess: "Request access", refreshStatus: "Refresh status",
  health: "Health data (steps)", devtools: "DevTools remote debugging", scriptBackend: "Script backend",
  browserPreview: "Browser preview · native tools and proxy unavailable",
  bridgeConnected: "Android WebView · Kotlin bridge connected",
  sessions: "Sessions", newChat: "New chat", send: "Send", stop: "Stop generation",
  emptyTitle: "What can I help with today?", emptyBody: "I can inspect device status, storage, and photos, then summarize what I find.",
  missingKey: "No API Key configured. Model requests will fail.", goSettings: "Open settings", requestFailed: "Request failed",
  inputPlaceholder: "Message AndAgent…", inputNoKey: "Add an API Key in Settings…",
  inputRecording: "Recording… tap again to stop", inputTranscribing: "Transcribing…",
  composerHint: "Enter to send · Shift+Enter for a newline · Review device tool results carefully",
  thinking: "Reasoning", thinkingNow: "Thinking…", copied: "Copied", copy: "Copy",
  renderFailed: "This message could not be rendered.", aborted: "Stopped (usage unavailable)",
  buildUnknown: "unknown", noticesUnavailable: "Component notices could not be loaded.",
};

export function getLanguageMode(): LanguageMode {
  const value = localStorage.getItem(KEY);
  return value === "zh" || value === "en" ? value : "system";
}
export function setLanguageMode(mode: LanguageMode): void {
  localStorage.setItem(KEY, mode);
  window.dispatchEvent(new Event("andagent:language"));
}
function currentLanguage(): "zh" | "en" {
  const mode = getLanguageMode();
  return mode === "system" ? (navigator.language.toLowerCase().startsWith("zh") ? "zh" : "en") : mode;
}
const subscribe = (listener: () => void) => {
  window.addEventListener("andagent:language", listener);
  return () => window.removeEventListener("andagent:language", listener);
};
export function useI18n() {
  const language = useSyncExternalStore(subscribe, currentLanguage, () => "en" as const);
  return { language, mode: getLanguageMode(), t: (key: Key) => language === "zh" ? zh[key] : en[key] };
}
