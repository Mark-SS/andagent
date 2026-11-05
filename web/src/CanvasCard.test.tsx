import { fireEvent, render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { CanvasCard } from "./CanvasCard";

describe("CanvasCard data channel", () => {
  it("posts the first frame after iframe load and later live data updates", () => {
    const code = JSON.stringify({ code: "ctx.fillText(String(data.value), 0, 0)", width: 320, height: 200 });
    const view = render(<CanvasCard code={code} data={{ value: 1 }} />);
    const frame = view.container.querySelector("iframe")!;
    const post = vi.spyOn(frame.contentWindow!, "postMessage");
    fireEvent.load(frame);
    expect(post).toHaveBeenCalledWith({ type: "canvas", spec: expect.objectContaining({ data: { value: 1 } }) }, "*");
    view.rerender(<CanvasCard code={code} data={{ value: 2 }} />);
    expect(post).toHaveBeenCalledWith({ type: "canvas", spec: expect.objectContaining({ data: { value: 2 } }) }, "*");
    view.unmount();
  });
});
