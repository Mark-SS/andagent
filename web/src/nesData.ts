import type { EmulatorData } from "jsnes";

export const NES_ROM_LIMIT = 2 * 1024 * 1024;

export function decodeNesRom(name: string, base64: string): Uint8Array {
  if (!/\.nes$/i.test(name)) throw new Error("只支持 .nes 文件");
  if (base64.length > Math.ceil(NES_ROM_LIMIT / 3) * 4 + 4) throw new Error("ROM 超过 2MB");
  const binary = atob(base64);
  if (binary.length > NES_ROM_LIMIT) throw new Error("ROM 超过 2MB");
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  if (bytes.length < 16 || bytes[0] !== 0x4e || bytes[1] !== 0x45 || bytes[2] !== 0x53 || bytes[3] !== 0x1a) {
    throw new Error("无效 NES 文件头");
  }
  return bytes;
}

export function romSaveName(bytes: Uint8Array): string {
  let hash = 0x811c9dc5;
  for (const byte of bytes) hash = Math.imul(hash ^ byte, 0x01000193);
  return `nes-${(hash >>> 0).toString(16).padStart(8, "0")}.json`;
}

export function serializeNesState(romName: string, state: EmulatorData): string {
  return JSON.stringify({ format: "andagent-nes-v1", romName, state });
}

export function parseNesState(content: string, romName: string): EmulatorData {
  const data = JSON.parse(content) as { format?: string; romName?: string; state?: EmulatorData };
  if (data.format !== "andagent-nes-v1" || data.romName !== romName || !data.state?.cpu || !data.state?.ppu || !data.state?.mmap || !data.state?.papu) {
    throw new Error("存档不属于当前游戏或格式无效");
  }
  return data.state;
}
