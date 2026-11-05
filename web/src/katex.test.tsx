import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import ReactMarkdown from "react-markdown";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";

// Smoke test: the same plugin pipeline the chat Markdown uses must turn
// $...$ / $$...$$ into KaTeX markup.
describe("KaTeX math rendering", () => {
  it("renders inline and block math via remark-math + rehype-katex", () => {
    const { container } = render(
      <ReactMarkdown remarkPlugins={[remarkMath]} rehypePlugins={[rehypeKatex]}>
        {"行内 $E=mc^2$ 与块级：$$\\\\int_0^1 x\\\\,dx$$"}
      </ReactMarkdown>,
    );
    expect(container.querySelectorAll(".katex").length).toBeGreaterThanOrEqual(2);
    expect(container.querySelector(".katex-html")?.textContent).toContain("E");
    // LaTeX source must not leak as raw text
    expect(container.textContent).not.toContain("$E=mc^2$");
  });
});
