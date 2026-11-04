import { useEffect, useState } from "react";
import type { ToolCallMessagePartProps } from "@assistant-ui/react";

const LABELS: Record<string, string> = {
  get_time: "获取时间",
  device_info: "设备信息",
  storage_scan: "存储扫描",
  album_stats: "相册统计",
};

const ICONS: Record<string, string> = {
  get_time: "◷",
  device_info: "▤",
  storage_scan: "◍",
  album_stats: "▣",
};

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

export function ToolCard(props: ToolCallMessagePartProps) {
  const running = props.result === undefined;
  const failed = !!props.isError;
  const [open, setOpen] = useState(false);

  useEffect(() => {
    setOpen(running);
  }, [running]);

  const label = LABELS[props.toolName] ?? props.toolName;
  const preview = running
    ? "正在执行…"
    : String(props.result ?? "")
        .split("\n")
        .slice(0, 2)
        .join(" · ")
        .slice(0, 120);

  return (
    <div
      className={`tool-card${failed ? " tool-error" : ""}${running ? " tool-running" : ""}`}
    >
      <button type="button" className="tool-summary" onClick={() => setOpen((v) => !v)}>
        <span className="tool-icon">{ICONS[props.toolName] ?? "⚙"}</span>
        <span className="tool-name">{label}</span>
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
      {open && (
        <div className="tool-body">
          <div className="tool-section">
            <div className="tool-label">参数</div>
            <pre className="tool-pre">{formatArgs(props.args)}</pre>
          </div>
          {!running && (
            <div className="tool-section">
              <div className="tool-label">结果</div>
              <pre className="tool-pre tool-result">{String(props.result ?? "")}</pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
