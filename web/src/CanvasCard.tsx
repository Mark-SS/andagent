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
html,body{margin:0;background:#0b0d11;overflow:hidden}
canvas{display:block;width:100%;height:100%}
</style></head><body><canvas id="c"></canvas><script>
var canvas=document.getElementById('c'),ctx=canvas.getContext('2d');
var spec=null,started=0,raf=0;
function frame(){
  if(!spec)return;
  var t=(Date.now()-started)/1000;
  ctx.save();ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,canvas.width,canvas.height);
  try{ new Function('ctx','t','w','h',spec.code)(ctx,t,canvas.width,canvas.height); }
  catch(e){ ctx.fillStyle='#ff9c9c';ctx.font='14px sans-serif';ctx.fillText(String(e),12,24); }
  ctx.restore();
  if(spec.animate)raf=requestAnimationFrame(frame);
}
addEventListener('message',function(ev){
  var d=ev.data||{};
  if(d.type!=='canvas')return;
  cancelAnimationFrame(raf);
  spec=d.spec;
  canvas.width=spec.width;canvas.height=spec.height;
  started=Date.now();
  frame();
});
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

export function CanvasCard({ code }: { code: string }) {
  const spec = useMemo(() => parseSpec(code), [code]);
  const frameRef = useRef<HTMLIFrameElement | null>(null);
  const [visible, setVisible] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);

  useEffect(() => {
    const frame = frameRef.current;
    if (!frame || !spec) return;
    const post = () => frame.contentWindow?.postMessage({ type: "canvas", spec }, "*");
    const timer = window.setInterval(() => {
      if (frame.dataset.ready !== "1") return;
      window.clearInterval(timer);
      post();
    }, 60);
    const onLoad = () => { frame.dataset.ready = "1"; post(); };
    frame.addEventListener("load", onLoad);
    return () => {
      window.clearInterval(timer);
      frame.removeEventListener("load", onLoad);
    };
  }, [spec, visible]);

  if (!spec) return <div className="rich-error">Canvas 数据格式错误</div>;
  if (!visible) return <div className="canvas-card"><button type="button" className="webview-toggle" onClick={() => setVisible(true)}>显示画布</button></div>;

  return (
    <div className={`canvas-card${fullscreen ? " fullscreen" : ""}`}>
      <div className="webview-bar">
        <span className="webview-uri">🎨 {spec.title ?? `Canvas ${spec.width}×${spec.height}`}</span>
        <button type="button" onClick={() => setFullscreen((v) => !v)}>{fullscreen ? "退出全屏" : "全屏"}</button>
        <button type="button" onClick={() => { setFullscreen(false); setVisible(false); }}>关闭</button>
      </div>
      <iframe
        ref={frameRef}
        title={spec.title ?? "Canvas 绘图"}
        sandbox="allow-scripts"
        srcDoc={SANDBOX_DOC}
      />
    </div>
  );
}
