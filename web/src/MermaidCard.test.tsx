import { beforeEach, describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MermaidCard } from "./MermaidCard";

// Illegal mermaid syntax must land on the error card (message + source
// echoed back) so the agent can read it and fix the diagram next turn.
// Note: the happy path (valid syntax → SVG) is not covered here because
// jsdom lacks the SVG layout APIs mermaid's renderer needs; it is verified
// in the built app instead.
describe("MermaidCard", () => {
  beforeEach(() => localStorage.setItem("andagent.language", "zh"));
  it("shows the error card for illegal mermaid syntax", async () => {
    render(<MermaidCard code={"flowchart TD\n  A--->B--->C((("} />);
    await waitFor(
      () => {
        expect(screen.getByText("Mermaid 图渲染失败")).toBeTruthy();
      },
      { timeout: 30000 },
    );
    expect(document.querySelector(".mermaid-error-msg")?.textContent).toBeTruthy();
    expect(document.querySelector(".mermaid-source")?.textContent).toContain("flowchart TD");
    // no SVG was rendered
    expect(document.querySelector(".mermaid-figure")).toBeNull();
  }, 30000);
});
