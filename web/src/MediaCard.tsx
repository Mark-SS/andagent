import { useState } from "react";

export type MediaKind = "audio" | "video";

export function mediaSource(uri: string): string | null {
  if (/^https:\/\//i.test(uri)) return uri;
  if (/^\/local\//.test(uri)) return `https://appassets.androidplatform.net${uri}`;
  const marker = "/Android/data/com.dc.andagent/files/";
  const pos = uri.indexOf(marker);
  if (pos < 0) return null;
  const relative = uri.slice(pos + marker.length);
  if (!relative || relative.split("/").some((part) => part === ".." || part === ".")) return null;
  return `https://appassets.androidplatform.net/local/${relative.split("/").map(encodeURIComponent).join("/")}`;
}

export function MediaCard({ kind, uri, title, poster }: { kind: MediaKind; uri: string; title?: string; poster?: string }) {
  const [duration, setDuration] = useState(0);
  const src = mediaSource(uri);
  const filename = title || decodeURIComponent(uri.split("/").pop() || (kind === "audio" ? "音频" : "视频"));
  if (!src) return <div className="media-card"><div className="media-title">{filename}</div><div className="media-error">文件不在应用媒体目录中，无法在对话内播放</div></div>;
  const time = duration ? `${Math.floor(duration / 60)}:${String(Math.floor(duration % 60)).padStart(2, "0")}` : "";
  return <div className="media-card">
    <div className="media-title">{kind === "audio" ? "🎵" : "🎬"} {filename}{time && <span className="media-duration">{time}</span>}</div>
    {kind === "audio"
      ? <audio controls preload="metadata" src={src} onLoadedMetadata={(event) => setDuration(event.currentTarget.duration)} />
      : <video controls preload="metadata" src={src} poster={poster ? mediaSource(poster) || undefined : undefined} onLoadedMetadata={(event) => setDuration(event.currentTarget.duration)} />}
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
