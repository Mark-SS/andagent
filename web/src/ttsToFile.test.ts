import { describe, expect, it } from "vitest";
import { EXTRA_TOOL_DEFS } from "./extraTools";
import { mediaFromResult, mediaSource } from "./MediaCard";

describe("tts_to_file playback handoff", () => {
  it("registers text as required and turns the returned path into a playable local route", () => {
    const tool = EXTRA_TOOL_DEFS.find((item) => item.name === "tts_to_file");
    expect(tool?.parameters.required).toContain("text");
    const path = "/storage/emulated/0/Android/data/com.dc.andagent/files/exports/tts_123.wav";
    expect(mediaFromResult(JSON.stringify({ path, size: 1200 }))).toEqual({ kind: "audio", uri: path });
    expect(mediaSource(path)).toBe("https://appassets.androidplatform.net/local/exports/tts_123.wav");
  });
});
