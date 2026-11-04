import { useState } from "react";
import { execTool } from "./bridge";

type Photo = { id?: string; uri?: string; name?: string; thumbnailBase64?: string; thumb?: string };

export function PhotosCard({ code }: { code: string }) {
  const [error, setError] = useState("");
  let photos: Photo[] = [];
  try { const data = JSON.parse(code); photos = Array.isArray(data) ? data : data.photos ?? []; } catch { return <div className="rich-error">照片数据格式错误</div>; }
  return <div className="photos-grid">{photos.map((photo, i) => {
    const src = photo.thumbnailBase64 ? `data:image/jpeg;base64,${photo.thumbnailBase64}` : photo.thumb;
    return <button type="button" key={photo.id ?? photo.uri ?? i} onClick={async () => {
      const result = await execTool("album_open", { id: photo.id, uri: photo.uri });
      if (!result.ok) setError(result.result);
    }}><img src={src} alt={photo.name ?? `照片 ${i + 1}`} loading="lazy" /><span>{photo.name ?? `照片 ${i + 1}`}</span></button>;
  })}{error && <div className="rich-error">{error}</div>}</div>;
}

export function KvCard({ code }: { code: string }) {
  let entries: [string, string][];
  try {
    const parsed = JSON.parse(code);
    entries = Object.entries(parsed).map(([key, value]) => [key, typeof value === "object" ? JSON.stringify(value) : String(value)]);
  } catch {
    entries = code.split("\n").map(line => { const at = line.indexOf(":"); return at < 0 ? null : [line.slice(0, at).trim(), line.slice(at + 1).trim()] as [string, string]; }).filter((row): row is [string, string] => !!row);
  }
  return <dl className="kv-card">{entries.map(([key, value], i) => <div key={i}><dt>{key}</dt><dd>{value}</dd></div>)}</dl>;
}

export function GeoCard({ code }: { code: string }) {
  const [error, setError] = useState("");
  let data: { lat: number; lng: number; label?: string };
  try { data = JSON.parse(code); } catch { return <div className="rich-error">地图数据格式错误</div>; }
  const { lat, lng, label } = data;
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return <div className="rich-error">坐标无效</div>;
  const bbox = `${lng - .01},${lat - .01},${lng + .01},${lat + .01}`;
  const src = `https://www.openstreetmap.org/export/embed.html?bbox=${encodeURIComponent(bbox)}&marker=${lat}%2C${lng}`;
  return <div className="geo-card"><div>{label ?? `${lat}, ${lng}`}</div><iframe title={label ?? "地图"} src={src} loading="lazy" /><button type="button" onClick={async () => {
    const result = await execTool("open_uri", { uri: `geo:${lat},${lng}` });
    if (!result.ok) setError(result.result);
  }}>在地图应用打开</button>{error && <div className="rich-error">{error}</div>}</div>;
}
