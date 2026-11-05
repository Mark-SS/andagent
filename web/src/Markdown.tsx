import { memo, useMemo, useState } from "react";
import {
  MarkdownTextPrimitive,
  type SyntaxHighlighterProps,
} from "@assistant-ui/react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import hljs from "highlight.js";
import { ChartCard } from "./ChartCard";
import { MermaidCard } from "./MermaidCard";
import { MediaCard } from "./MediaCard";
import { PhotosCard, ImageCard, KvCard, TableCard, JsonTreeCard, GeoCard } from "./RichCards";
import { CameraCard } from "./CameraCard";
import { TerminalCard } from "./TerminalCard";
import { CanvasCard } from "./CanvasCard";
import { ShaderCard } from "./ShaderCard";
import { WidgetPreviewCard } from "./WidgetPreviewCard";

function CodeBlock({ language, code, components: { Pre, Code } }: SyntaxHighlighterProps) {
  const [copied, setCopied] = useState(false);
  const kind = (language ?? "").toLowerCase();
  const isChart = ["chart", "photos", "image", "kv", "table", "json-tree", "geo", "audio", "video", "camera", "widget", "canvas", "shader", "terminal", "mermaid"].includes(kind);

  const html = useMemo(() => {
    if (isChart) return "";
    try {
      if (language && hljs.getLanguage(language)) {
        return hljs.highlight(code, { language }).value;
      }
      return hljs.highlightAuto(code).value;
    } catch {
      return code.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" })[c]!);
    }
  }, [code, language, isChart]);

  // ```chart fences carry ECharts option JSON, not source code.
  if (kind === "chart") return <ChartCard code={code} />;
  if (kind === "mermaid") return <MermaidCard code={code} />;
  if (kind === "photos") return <PhotosCard code={code} />;
  if (kind === "image") return <ImageCard code={code} />;
  if (kind === "kv") return <KvCard code={code} />;
  if (kind === "table") return <TableCard code={code} />;
  if (kind === "json-tree") return <JsonTreeCard code={code} />;
  if (kind === "canvas") return <CanvasCard code={code} />;
  if (kind === "shader") return <ShaderCard code={code} />;
  if (kind === "geo") return <GeoCard code={code} />;
  if (kind === "camera") return <CameraCard code={code} />;
  if (kind === "terminal") return <TerminalCard code={code} />;
  if (kind === "widget") return <WidgetPreviewCard code={code} />;
  if (kind === "audio" || kind === "video") {
    try {
      const data = JSON.parse(code) as { uri?: string; title?: string; poster?: string };
      if (typeof data.uri === "string") return <MediaCard kind={kind} uri={data.uri} title={data.title} poster={data.poster} />;
    } catch { /* invalid media JSON */ }
    return <div className="media-error">媒体数据格式错误</div>;
  }

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1400);
    } catch {
      /* clipboard unavailable */
    }
  };

  return (
    <div className="code-wrap">
      <div className="code-head">
        <span className="code-lang">{language || "text"}</span>
        <button type="button" className="code-copy" onClick={copy}>
          {copied ? "已复制" : "复制"}
        </button>
      </div>
      <Pre className="code-block">
        <Code
          className={`hljs language-${language ?? "text"}`}
          dangerouslySetInnerHTML={{ __html: html }}
        />
      </Pre>
    </div>
  );
}

const MARKDOWN_COMPONENTS = {
  SyntaxHighlighter: CodeBlock,
  a: (props: React.AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a {...props} target="_blank" rel="noreferrer noopener" />
  ),
};

export const MarkdownText = memo(function MarkdownText() {
  return (
    <MarkdownTextPrimitive
      className="md"
      remarkPlugins={[remarkGfm, remarkMath]}
      rehypePlugins={[rehypeKatex]}
      components={MARKDOWN_COMPONENTS}
      defer
    />
  );
});
