import { memo, useMemo, useState } from "react";
import {
  MarkdownTextPrimitive,
  type SyntaxHighlighterProps,
} from "@assistant-ui/react-markdown";
import remarkGfm from "remark-gfm";
import hljs from "highlight.js";
import { ChartCard } from "./ChartCard";

function CodeBlock({ language, code, components: { Pre, Code } }: SyntaxHighlighterProps) {
  const [copied, setCopied] = useState(false);
  const isChart = (language ?? "").toLowerCase() === "chart";

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
  if (isChart) return <ChartCard code={code} />;

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
      remarkPlugins={[remarkGfm]}
      components={MARKDOWN_COMPONENTS}
      defer
    />
  );
});
