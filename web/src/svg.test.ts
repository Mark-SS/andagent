import { beforeEach, describe, it, expect } from "vitest";
import { parseSvg } from "./SvgCard";

// R39: ```svg fences render only after DOMParser confirms the root node is
// <svg> — this is the injection guard for dangerouslySetInnerHTML.
describe("SvgCard.parseSvg", () => {
  beforeEach(() => localStorage.setItem("andagent.language", "zh"));
  it("accepts a plain SVG document", () => {
    const { svg, error } = parseSvg('<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><circle r="4" cx="5" cy="5"/></svg>');
    expect(error).toBeNull();
    expect(svg?.nodeName.toLowerCase()).toBe("svg");
  });

  it("rejects non-SVG root nodes (HTML injection)", () => {
    const { svg, error } = parseSvg('<div onclick="steal()">x</div>');
    expect(svg).toBeNull();
    expect(error).toContain("根节点不是 <svg>");
  });

  it("rejects malformed markup", () => {
    const { svg, error } = parseSvg("<svg><circle><</svg>");
    expect(svg).toBeNull();
    expect(error).toContain("解析失败");
  });

  it("rejects plain text", () => {
    const { svg, error } = parseSvg("just some text");
    expect(svg).toBeNull();
    // jsdom treats bare text as a parse error ("text data outside of root node").
    expect(error).toBeTruthy();
  });
});
