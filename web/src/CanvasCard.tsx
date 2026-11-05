import { useI18n } from "./i18n";
import { useEffect, useMemo, useRef, useState } from "react";

/**
 * ```canvas fence: scripted 2D drawing inside a sandboxed iframe.
 *
 * The drawing code is untrusted (it comes from the model), so it never runs in
 * the app's WebView context: a `sandbox="allow-scripts"` iframe has an opaque
 * origin and cannot reach AndroidBridge, localStorage or the parent DOM.
 *
 * Fence payload is either plain JS source or a JSON object:
 *   { "width": 640, "height": 360, "animate": true, "code": "...", "title": "..." }
 * The script receives (ctx, t, w, h) where t is seconds since start.
 */
type CanvasSpec = {
  width: number;
  height: number;
  animate: boolean;
  code: string;
  title?: string;
};

const SANDBOX_DOC = `<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;height:100%;background:#0b0d11;overflow:hidden}
canvas{display:block;width:100%;height:100%}
</style></head><body><canvas id="c"></canvas><script>
var canvas=document.getElementById('c'),ctx=canvas.getContext('2d');
var spec=null,started=0,raf=0;
// 显示区尺寸驱动内部缓冲：canvas 内部分辨率始终跟随 iframe 实际渲染尺寸，
// 绘图坐标 w/h 即显示区大小——绘图内容自动铺满，不再有未绘制的黑色空区。
function fit(){
  var r=canvas.getBoundingClientRect();
  var w=Math.max(64,Math.round(r.width)),h=Math.max(64,Math.round(r.height));
  if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h;return true;}
  return false;
}
function frame(){
  if(!spec)return;
  if(fit()){started=Date.now();cancelAnimationFrame(raf);}
  var t=(Date.now()-started)/1000;
  ctx.save();ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,canvas.width,canvas.height);
  try{ new Function('ctx','t','w','h','data',spec.code)(ctx,t,canvas.width,canvas.height,spec.data); }
  catch(e){ ctx.fillStyle='#ff9c9c';ctx.font='14px sans-serif';ctx.fillText(String(e),12,24); }
  ctx.restore();
  if(spec.animate)raf=requestAnimationFrame(frame);
}
addEventListener('message',function(ev){
  var d=ev.data||{};
  if(d.type!=='canvas')return;
  cancelAnimationFrame(raf);
  spec=d.spec;
  started=Date.now();
  frame();
});
addEventListener('resize',function(){ if(spec)frame(); });
frame();
</script></body></html>`;

function parseSpec(code: string): CanvasSpec | null {
  const trimmed = code.trim();
  if (trimmed.startsWith("{")) {
    try {
      const raw = JSON.parse(trimmed) as Record<string, unknown>;
      const body = typeof raw.code === "string" ? raw.code : "";
      if (!body) return null;
      return {
        width: clampDimension(raw.width, 640),
        height: clampDimension(raw.height, 360),
        animate: raw.animate === true,
        code: body,
        title: typeof raw.title === "string" ? raw.title : undefined,
      };
    } catch {
      return null;
    }
  }
  return { width: 640, height: 360, animate: /\bt\b|requestAnimationFrame/.test(trimmed), code: trimmed };
}

function clampDimension(value: unknown, fallback: number): number {
  const n = typeof value === "number" ? value : Number(value);
  return Number.isFinite(n) ? Math.min(2048, Math.max(64, Math.round(n))) : fallback;
}

export function CanvasCard({ code, data }: { code: string; data?: unknown }) {
  const { t } = useI18n();
  const spec = useMemo(() => parseSpec(code), [code]);
  const frameRef = useRef<HTMLIFrameElement | null>(null);
  const [visible, setVisible] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);

  useEffect(() => {
    const frame = frameRef.current;
    if (!frame || !spec || frame.dataset.ready !== "1") return;
    frame.contentWindow?.postMessage({ type: "canvas", spec: { ...spec, data } }, "*");
  }, [spec, data, visible]);

  const onFrameLoad = () => {
    const frame = frameRef.current;
    if (!frame || !spec) return;
    frame.dataset.ready = "1";
    frame.contentWindow?.postMessage({ type: "canvas", spec: { ...spec, data } }, "*");
  };

  if (!spec) return <div className="rich-error">{t("canvasInvalid")}</div>;
  if (!visible) return <div className="canvas-card"><button type="button" className="webview-toggle" onClick={() => setVisible(true)}>{t("showCanvas")}</button></div>;

  return (
    <div className={`canvas-card${fullscreen ? " fullscreen" : ""}`}>
      <div className="webview-bar">
        <span className="webview-uri">🎨 {spec.title ?? `Canvas ${spec.width}×${spec.height}`}</span>
        <button type="button" onClick={() => setFullscreen((v) => !v)}>{fullscreen ? t("exitFullscreen") : t("fullscreen")}</button>
        <button type="button" onClick={() => { setFullscreen(false); setVisible(false); }}>{t("close")}</button>
      </div>
      <iframe
        ref={frameRef}
        title={spec.title ?? t("canvasDrawing")}
        sandbox="allow-scripts"
        srcDoc={SANDBOX_DOC}
        onLoad={onFrameLoad}
      />
    </div>
  );
}
