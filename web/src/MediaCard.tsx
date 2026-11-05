import { useState } from "react";

export type MediaKind = "audio" | "video";

/** Local paths the native /local/ route can serve (see LocalMediaHandler.kt). */
function localPathToRoute(uri: string): string | null {
  if (/^\/local\//.test(uri)) return uri;
  const appMarker = "/Android/data/com.dc.andagent/files/";
  const appPos = uri.indexOf(appMarker);
  if (appPos >= 0) {
    const relative = uri.slice(appPos + appMarker.length);
    if (!relative || relative.split("/").some((part) => part === ".." || part === ".")) return null;
    return `/local/${relative.split("/").map(encodeURIComponent).join("/")}`;
  }
  // R39: Download/andagent/（工具产出）与公共 Download/（只读）也走 /local/。
  const dl = uri.match(/^(?:\/sdcard|\/storage\/emulated\/0)\/Download\/?(.*)$/);
  if (dl) {
    const relative = dl[1] ?? "";
    if (!relative || relative.split("/").some((part) => part === ".." || part === ".")) return null;
    return `/local/Download/${relative.split("/").map(encodeURIComponent).join("/")}`;
  }
  return null;
}

export function mediaSource(uri: string): string | null {
  if (/^https:\/\//i.test(uri)) return uri;
  const route = localPathToRoute(uri);
  return route ? `https://appassets.androidplatform.net${route}` : null;
}

function describeMediaError(code: number | undefined, src: string): string {
  switch (code) {
    case 1: return "加载被中断";
    case 2: return "网络错误：本地文件服务不可达";
    case 3: return "解码失败：WebView 不支持该音频/视频编码格式";
    case 4: return "资源不可用：文件不存在、类型不支持或路径不在可访问目录（应用媒体目录 / Download/andagent / Download）";
    default: return `加载失败（${src.slice(0, 80)}）`;
  }
}

export function MediaCard({ kind, uri, title, poster }: { kind: MediaKind; uri: string; title?: string; poster?: string }) {
  const [duration, setDuration] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const src = mediaSource(uri);
  const filename = title || decodeURIComponent(uri.split("/").pop() || (kind === "audio" ? "音频" : "视频"));
  if (!src) return <div className="media-card"><div className="media-title">{filename}</div><div className="media-error">文件不在应用媒体目录中，无法在对话内播放</div></div>;
  const time = duration ? `${Math.floor(duration / 60)}:${String(Math.floor(duration % 60)).padStart(2, "0")}` : "";
  const onError = (event: React.SyntheticEvent<HTMLMediaElement>) =>
    setError(describeMediaError(event.currentTarget.error?.code, src));
  return <div className="media-card">
    <div className="media-title">{kind === "audio" ? "🎵" : "🎬"} {filename}{time && <span className="media-duration">{time}</span>}</div>
    {error
      ? <div className="media-error">⚠️ {error}</div>
      : kind === "audio"
        ? <audio controls preload="metadata" src={src} onLoadedMetadata={(event) => setDuration(event.currentTarget.duration)} onError={onError} />
        : <video controls preload="metadata" src={src} poster={poster ? mediaSource(poster) || undefined : undefined} onLoadedMetadata={(event) => setDuration(event.currentTarget.duration)} onError={onError} />}
  </div>;
}

export function mediaFromResult(raw: string): { kind: MediaKind; uri: string } | null {
  const candidates: string[] = [];
  try {
    const value: unknown = JSON.parse(raw);
    if (value && typeof value === "object") {
      const record = value as Record<string, unknown>;
      for (const key of ["previewPath", "path", "uri", "filePath"]) if (typeof record[key] === "string") candidates.push(record[key]);
    }
  } catch { /* plain text result */ }
  candidates.push(...(raw.match(/(?:\/[^\s（）"']+\.(?:mp3|m4a|mp4|webm|ogg|wav|flac))/gi) || []));
  for (const uri of candidates) {
    if (!mediaSource(uri)) continue;
    if (/\.(mp3|m4a|ogg|wav|flac)(?:\?|$)/i.test(uri)) return { kind: "audio", uri };
    if (/\.(mp4|webm)(?:\?|$)/i.test(uri)) return { kind: "video", uri };
  }
  return null;
}
