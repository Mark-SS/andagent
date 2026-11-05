import { useEffect, useRef, useState, useId } from "react";

type MermaidModule = typeof import("mermaid");

let mermaidPromise: Promise<MermaidModule> | null = null;

/** Lazy-load mermaid on first use so it stays out of the main bundle. */
function loadMermaid(): Promise<MermaidModule> {
  if (!mermaidPromise) {
    mermaidPromise = import("mermaid").then((mod) => {
      mod.default.initialize({ startOnLoad: false, theme: "dark", securityLevel: "strict" });
      return mod;
    });
  }
  return mermaidPromise;
}

/**
 * ```mermaid fenced code blocks: parse + render to SVG via mermaid.render.
 * Parse/render failures show an error card with the original message so the
 * agent can read it back and fix the diagram in the next turn.
 */
export function MermaidCard({ code }: { code: string }) {
  const [svg, setSvg] = useState("");
  const [error, setError] = useState("");
  const [expanded, setExpanded] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const reactId = useId().replace(/[^a-zA-Z0-9]/g, "");
  const renderSeq = useRef(0);

  useEffect(() => {
    const seq = ++renderSeq.current;
    let cancelled = false;
    (async () => {
      try {
        const { default: mermaid } = await loadMermaid();
        const { svg: rendered } = await mermaid.render(`mermaid-${reactId}-${seq}`, code);
        if (!cancelled && seq === renderSeq.current) {
          setSvg(rendered);
          setError("");
        }
      } catch (e) {
        // mermaid leaves a dangling error element in the DOM on parse failure.
        document.getElementById(`dmermaid-${reactId}-${seq}`)?.remove();
        if (!cancelled && seq === renderSeq.current) {
          setSvg("");
          setError(e instanceof Error ? e.message : String(e));
        }
      }
    })();
    return () => { cancelled = true; };
  }, [code, reactId]);

  if (error) {
    return <div className="mermaid-card mermaid-error">
      <div className="mermaid-error-head">Mermaid 图渲染失败</div>
      <pre className="mermaid-error-msg">{error}</pre>
      <pre className="mermaid-source">{code}</pre>
    </div>;
  }

  return <div className="mermaid-card">
    <div
      ref={containerRef}
      className="mermaid-figure"
      role={svg ? "button" : undefined}
      tabIndex={svg ? 0 : undefined}
      aria-label={svg ? "全屏查看图表" : undefined}
      onClick={() => svg && setExpanded(true)}
      onKeyDown={(e) => { if (svg && (e.key === "Enter" || e.key === " ")) setExpanded(true); }}
      dangerouslySetInnerHTML={{ __html: svg }}
    />
    {!svg && !error && <div className="mermaid-loading">正在渲染图表…</div>}
    {expanded && svg && <div className="image-lightbox" role="dialog" aria-modal="true" aria-label="图表预览" onClick={() => setExpanded(false)}>
      <button type="button" onClick={() => setExpanded(false)} aria-label="关闭预览">×</button>
      <div className="mermaid-lightbox-body" dangerouslySetInnerHTML={{ __html: svg }} />
    </div>}
  </div>;
}
