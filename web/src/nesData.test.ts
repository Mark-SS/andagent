import { describe, expect, it } from "vitest";
import type { EmulatorData } from "jsnes";
import { decodeNesRom, parseNesState, romSaveName, serializeNesState } from "./nesData";

const header = Uint8Array.from([78, 69, 83, 26, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
const base64 = btoa(String.fromCharCode(...header));

describe("NES ROM and state", () => {
  it("accepts only bounded .nes data with the NES header", () => {
    expect(decodeNesRom("game.nes", base64)).toEqual(header);
    expect(() => decodeNesRom("game.bin", base64)).toThrow();
    expect(() => decodeNesRom("game.nes", btoa("invalid"))).toThrow();
  });
  it("round trips a save and binds it to the selected ROM", () => {
    const name = romSaveName(header);
    const state = { cpu: { value: 1 }, ppu: {}, mmap: {}, papu: {} } as EmulatorData;
    expect(parseNesState(serializeNesState(name, state), name)).toEqual(state);
    expect(() => parseNesState(serializeNesState(name, state), "nes-00000000.json")).toThrow();
  });
});
