import { useEffect, useMemo, useState } from "react";
import type { ToolCallMessagePartProps } from "@assistant-ui/react";

interface ToolMeta {
  label: string;
  icon: string;
  destructive?: boolean;
}

const META: Record<string, ToolMeta> = {
  get_time: { label: "获取时间", icon: "🕐" },
  device_info: { label: "设备信息", icon: "📱" },
  storage_scan: { label: "存储扫描", icon: "💾" },
  album_stats: { label: "相册统计", icon: "🖼️" },
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
  shell_exec: { label: "运行脚本", icon: "⌨️" },
  pkg_install: { label: "安装软件包", icon: "📦", destructive: true },
  screen_capture: { label: "屏幕截图", icon: "🖼️" },
  screen_record_h264: { label: "H.264 录屏", icon: "🎥" },
  input_tap: { label: "点击屏幕", icon: "👆", destructive: true },
  input_swipe: { label: "滑动屏幕", icon: "👆", destructive: true },
  input_text: { label: "输入文本", icon: "⌨️", destructive: true },
  open_settings: { label: "打开系统设置", icon: "⚙️" },
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

export function ToolCard(props: ToolCallMessagePartProps) {
  const running = props.result === undefined;
  const failed = !!props.isError;
  const [open, setOpen] = useState(false);
  const [expanded, setExpanded] = useState(false);

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
  if (props.toolName === "screen_capture" && !running && !failed) {
    try { const result = JSON.parse(raw) as { thumbnailBase64?: string }; if (result.thumbnailBase64) captureImage = `data:image/jpeg;base64,${result.thumbnailBase64}`; } catch { /* no image */ }
  }

  const preview = running ? "正在执行…" : summarize(raw);

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
        <span className={`tool-chevron${open ? " open" : ""}`}>⌄</span>
      </button>

      {written && !failed && !running && (
        <div className="tool-confirm">
          <span aria-hidden="true">✓</span>
          <span>已写入设备数据，请核对下方结果</span>
        </div>
      )}

      {captureImage && <img src={captureImage} alt="屏幕截图缩略图" style={{ display: "block", maxWidth: "100%", borderRadius: 8, margin: "8px auto" }} />}

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
              </div>
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
