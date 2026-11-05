import { useEffect, useMemo, useState } from "react";
import type { ToolCallMessagePartProps } from "@assistant-ui/react";
import { WebViewCard } from "./WebViewCard";
import { MediaCard, mediaFromResult } from "./MediaCard";
import { PhotosCard } from "./RichCards";

interface ToolMeta {
  label: string;
  icon: string;
  destructive?: boolean;
}

const META: Record<string, ToolMeta> = {
  get_time: { label: "获取时间", icon: "🕐" },
  db_create: { label: "创建数据库", icon: "🗄️" },
  db_list: { label: "数据库列表", icon: "🗄️" },
  db_gui: { label: "打开数据库", icon: "🗄️" },
  db_tables: { label: "数据表列表", icon: "🗄️" },
  db_schema: { label: "数据表结构", icon: "🗄️" },
  db_exec: { label: "执行 SQL", icon: "🗄️", destructive: true },
  db_query: { label: "查询数据库", icon: "🗄️" },
  db_drop: { label: "删除数据库", icon: "🗑️", destructive: true },
  nfc_status: { label: "NFC 状态", icon: "📶" },
  nfc_read: { label: "读取 NFC", icon: "📶" },
  nfc_write_text: { label: "写入 NFC 文本", icon: "📶", destructive: true },
  nfc_write_uri: { label: "写入 NFC 链接", icon: "📶", destructive: true },
  nfc_mf_dump: { label: "MIFARE 扇区 dump", icon: "📶" },
  nfc_mf_write_block: { label: "MIFARE 写块", icon: "📶", destructive: true },
  nfc_ul_read: { label: "Ultralight 读页", icon: "📶" },
  nfc_ul_write_page: { label: "Ultralight 写页", icon: "📶", destructive: true },
  nfc_keys_manage: { label: "NFC key 库", icon: "🔑" },
  web_embed: { label: "内嵌浏览器", icon: "🌐" },
  device_info: { label: "设备信息", icon: "📱" },
  storage_scan: { label: "存储扫描", icon: "💾" },
  album_stats: { label: "相册统计", icon: "🖼️" },
  album_photos: { label: "相册照片", icon: "📸" },
  album_open: { label: "图库打开", icon: "🖼️" },
  calendar_list: { label: "日历账户", icon: "📅" },
  calendar_events: { label: "日程查询", icon: "🗓️" },
  calendar_add: { label: "创建日程", icon: "➕", destructive: true },
  calendar_delete: { label: "删除日程", icon: "🗑️", destructive: true },
  alarm_set: { label: "设置闹钟", icon: "⏰" },
  timer_set: { label: "设置计时器", icon: "⏱️" },
  battery_detail: { label: "电池详情", icon: "🔋" },
  wifi_info: { label: "Wi-Fi 信息", icon: "📶" },
  clipboard_write: { label: "写入剪贴板", icon: "📋" },
  clipboard_read: { label: "读取剪贴板", icon: "📋" },
  notify_send: { label: "发送通知", icon: "🔔" },
  app_list: { label: "应用列表", icon: "📦" },
  bt_scan: { label: "蓝牙扫描", icon: "📡" },
  bt_paired_list: { label: "已配对设备", icon: "🔵" },
  bt_connect: { label: "蓝牙连接", icon: "🔵" },
  bt_connect_direct: { label: "蓝牙直接连接", icon: "🔵", destructive: true },
  bt_toggle: { label: "蓝牙开关", icon: "🔵", destructive: true },
  wifi_connect: { label: "Wi-Fi 面板", icon: "📶" },
  wifi_connect_strong: { label: "Wi-Fi 连接", icon: "📶", destructive: true },
  wifi_saved_list: { label: "已存网络", icon: "📶" },
  wifi_forget: { label: "遗忘网络", icon: "📶", destructive: true },
  wifi_toggle: { label: "Wi-Fi 开关", icon: "📶", destructive: true },
  termux_setup: { label: "脚本环境", icon: "⌨️" },
  js_eval: { label: "QuickJS 脚本", icon: "📜" },
  script_save: { label: "保存脚本", icon: "💾" },
  script_list: { label: "脚本库", icon: "📜" },
  script_read: { label: "读取脚本", icon: "📄" },
  script_run: { label: "运行脚本", icon: "▶️" },
  script_delete: { label: "删除脚本", icon: "🗑️", destructive: true },
  script_check: { label: "检查语法", icon: "✓" },
  json_fix: { label: "修复 JSON", icon: "🔧" },
  shell_exec: { label: "运行脚本", icon: "⌨️" },
  pkg_install: { label: "安装软件包", icon: "📦", destructive: true },
  screen_capture: { label: "屏幕截图", icon: "🖼️" },
  image_edit: { label: "图片编辑", icon: "🎨" },
  image_preview: { label: "图片预览", icon: "🖼️" },
  image_info: { label: "图片信息", icon: "ℹ️" },
  image_collage: { label: "图片拼图", icon: "🧩" },
  screen_record_h264: { label: "H.264 录屏", icon: "🎥" },
  input_tap: { label: "点击屏幕", icon: "👆", destructive: true },
  input_swipe: { label: "滑动屏幕", icon: "👆", destructive: true },
  input_text: { label: "输入文本", icon: "⌨️", destructive: true },
  open_settings: { label: "打开系统设置", icon: "⚙️" },
  open_uri: { label: "跳转应用", icon: "🔗" },
  phone_call: { label: "拨打电话", icon: "📞" },
  location_get: { label: "获取位置", icon: "📍" },
  location_status: { label: "定位状态", icon: "🛰️" },
  sms_send: { label: "发送短信", icon: "💬" },
  mic_record: { label: "麦克风录音", icon: "🎤" },
  screen_record: { label: "录屏", icon: "🎥" },
  screen_record_stop: { label: "停止录屏", icon: "⏹️" },
  screen_record_list: { label: "录屏列表", icon: "🎞️" },
  file_download: { label: "下载文件", icon: "📥" },
  file_search: { label: "搜索文件", icon: "🔎" },
  file_write: { label: "写文件", icon: "📝", destructive: true },
  file_read: { label: "读文件", icon: "📄" },
  file_list: { label: "列文件", icon: "📂" },
  sensor_list: { label: "传感器列表", icon: "🧭" },
  sensor_read: { label: "读传感器", icon: "🧭" },
  steps_today: { label: "今日步数", icon: "👟" },
  steps_history: { label: "步数历史", icon: "👟" },
};

const COLLAPSE_LINES = 15;

function formatArgs(args: unknown): string {
  if (!args || (typeof args === "object" && Object.keys(args).length === 0)) {
    return "无参数";
  }
  try {
    return JSON.stringify(args, null, 2);
  } catch {
    return String(args);
  }
}

/** Pretty-print JSON tool results; everything else is shown verbatim. */
function formatResult(raw: string): { text: string; json: boolean } {
  const trimmed = raw.trim();
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
    return { text: raw, json: false };
  }
  try {
    return { text: JSON.stringify(JSON.parse(trimmed), null, 2), json: true };
  } catch {
    return { text: raw, json: false };
  }
}

function summarize(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return "";
  try {
    if (trimmed.startsWith("{")) {
      const obj = JSON.parse(trimmed) as Record<string, unknown>;
      const listKey = Object.keys(obj).find((key) => Array.isArray(obj[key]));
      if (listKey) {
        const count = (obj[listKey] as unknown[]).length;
        const total = typeof obj.count === "number" ? obj.count : count;
        return `${listKey}：${total} 条`;
      }
      const pairs = Object.keys(obj)
        .slice(0, 3)
        .map((key) => {
          const value = obj[key];
          return `${key}=${typeof value === "object" ? JSON.stringify(value) : String(value)}`;
        })
        .join(" · ");
      return pairs.slice(0, 120);
    }
    if (trimmed.startsWith("[")) {
      const arr = JSON.parse(trimmed) as unknown[];
      return `数组：${arr.length} 条`;
    }
  } catch {
    /* not JSON — fall through */
  }
  return trimmed
    .split("\n")
    .filter((line) => line.trim())
    .slice(0, 2)
    .join(" · ")
    .slice(0, 120);
}

function JsonNode({ label, value, depth = 0 }: { label: string; value: unknown; depth?: number }) {
  const [open, setOpen] = useState(depth < 1);
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value);
    return <div className="json-node" style={{ marginLeft: depth ? 12 : 0 }}>
      <button type="button" onClick={() => setOpen(!open)}>{open ? "▾" : "▸"} {label} <span>{Array.isArray(value) ? `[${entries.length}]` : `{${entries.length}}`}</span></button>
      {open && entries.map(([key, child]) => <JsonNode key={key} label={key} value={child} depth={depth + 1} />)}
    </div>;
  }
  return <div className="json-leaf" style={{ marginLeft: depth * 12 }}><span>{label}:</span> {JSON.stringify(value)}</div>;
}

export function ToolCard(props: ToolCallMessagePartProps) {
  const running = props.result === undefined;
  const failed = !!props.isError;
  const [open, setOpen] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [copied, setCopied] = useState(false);
  const [notice, setNotice] = useState("");
  useEffect(() => {
    if (!props.toolName.startsWith("nfc_")) return;
    const listener = (event: Event) => setNotice((event as CustomEvent<string>).detail);
    window.addEventListener("andagent:notice", listener);
    return () => window.removeEventListener("andagent:notice", listener);
  }, [props.toolName]);

  useEffect(() => {
    setOpen(running);
  }, [running]);

  const meta = META[props.toolName] ?? { label: props.toolName, icon: "⚙️" };
  const raw = String(props.result ?? "");
  const formatted = useMemo(() => formatResult(raw), [raw]);
  const lines = formatted.text.split("\n");
  const canFold = lines.length > COLLAPSE_LINES;
  const shown = canFold && !expanded ? lines.slice(0, COLLAPSE_LINES).join("\n") : formatted.text;
  const written = /^(WRITTEN|DELETED|TOGGLED)/.test(raw);
  let captureImage: string | null = null;
  if (["screen_capture", "image_edit", "image_preview", "image_collage", "devtools_screenshot"].includes(props.toolName) && !running && !failed) {
    try { const result = JSON.parse(raw) as { thumbnailBase64?: string; thumbnailMime?: string }; if (result.thumbnailBase64) captureImage = `data:${result.thumbnailMime ?? "image/jpeg"};base64,${result.thumbnailBase64}`; } catch { /* no image */ }
  }
  let beforeImage: string | null = null;
  if (["image_edit", "image_preview"].includes(props.toolName) && captureImage) {
    try { const result = JSON.parse(raw) as { sourceThumbnailBase64?: string }; if (result.sourceThumbnailBase64) beforeImage = `data:image/png;base64,${result.sourceThumbnailBase64}`; } catch { /* no image */ }
  }

  const preview = running ? (props.toolName.startsWith("nfc_") && !["nfc_status", "nfc_hce_status", "nfc_keys_manage"].includes(props.toolName) ? "请贴近 NFC 卡片，最多等待 60 秒…" : "正在执行…") : summarize(raw);
  const parsed = useMemo(() => { try { return formatted.json ? JSON.parse(raw) : null; } catch { return null; } }, [raw, formatted.json]);
  const media = !running && !failed && ["file_download", "mic_record", "screen_record_h264", "screen_record", "screen_record_stop"].includes(props.toolName) ? mediaFromResult(raw) : null;
  const webUri = props.toolName === "web_embed" && parsed?.type === "webview" && typeof parsed.uri === "string" ? parsed.uri : null;
  const photoGrid = !running && !failed && props.toolName === "album_photos" && Array.isArray(parsed?.photos) && parsed.photos.length > 0;

  return (
    <div
      className={`tool-card${failed ? " tool-error" : ""}${running ? " tool-running" : ""}${
        meta.destructive ? " tool-destructive" : ""
      }`}
    >
      <button type="button" className="tool-summary" onClick={() => setOpen((v) => !v)}>
        <span className="tool-icon" aria-hidden="true">
          {meta.icon}
        </span>
        <span className="tool-name">{meta.label}</span>
        {meta.destructive && <span className="tool-badge">写入</span>}
        <span className="tool-preview">{preview}</span>
        <span className={`tool-state${failed ? " state-error" : ""}`}>
          {running ? (
            <>
              <span className="dot-pulse" /> 执行中
            </>
          ) : failed ? (
            "失败"
          ) : (
            "完成"
          )}
        </span>
        <span className={`tool-chevron${open ? " open" : ""}`}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="m6 9 6 6 6-6" />
          </svg>
        </span>
      </button>

      {running && notice && <div className="tool-confirm">{notice}</div>}

      {written && !failed && !running && (
        <div className="tool-confirm">
          <span aria-hidden="true">✓</span>
          <span>已写入设备数据，请核对下方结果</span>
        </div>
      )}

      {webUri && <WebViewCard uri={webUri} />}
      {photoGrid && <PhotosCard code={JSON.stringify(parsed?.photos ?? [])} />}
      {media && <MediaCard kind={media.kind} uri={media.uri} />}
      {captureImage && <div className="tool-image-comparison">{beforeImage && <figure><img src={beforeImage} alt="编辑前" /><figcaption>编辑前</figcaption></figure>}<figure><img src={captureImage} alt="编辑后" /><figcaption>{beforeImage ? "编辑后" : "图片预览"}</figcaption></figure></div>}

      {open && (
        <div className="tool-body">
          <div className="tool-section">
            <div className="tool-label">参数</div>
            <pre className="tool-pre">{formatArgs(props.args)}</pre>
          </div>
          {!running && (
            <div className="tool-section">
              <div className="tool-label">
                结果{formatted.json ? " · JSON" : ""}
                {canFold ? ` · ${lines.length} 行` : ""}
                <button
                  type="button"
                  className="tool-copy"
                  onClick={async () => {
                    try {
                      await navigator.clipboard.writeText(raw);
                      setCopied(true);
                      window.setTimeout(() => setCopied(false), 1400);
                    } catch {
                      /* clipboard unavailable */
                    }
                  }}
                >
                  {copied ? "已复制" : "复制"}
                </button>
              </div>
              {formatted.json && parsed !== null && <details className="json-tree"><summary>树形查看</summary><JsonNode label="result" value={parsed} /></details>}
              <pre className="tool-pre tool-result">{captureImage ? "截图已保存；缩略图见上方" : shown}</pre>
              {canFold && (
                <button
                  type="button"
                  className="tool-expand"
                  onClick={() => setExpanded((v) => !v)}
                >
                  {expanded ? "收起" : `展开（还有 ${lines.length - COLLAPSE_LINES} 行）`}
                </button>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
