import { useEffect, useMemo, useRef, useState } from "react";
import { Controller, NES, type ButtonKey } from "jsnes";
import { execTool } from "./bridge";
import { decodeNesRom, parseNesState, romSaveName, serializeNesState } from "./nesData";

const KEYS: Record<string, ButtonKey> = {
  ArrowUp: Controller.BUTTON_UP, ArrowDown: Controller.BUTTON_DOWN,
  ArrowLeft: Controller.BUTTON_LEFT, ArrowRight: Controller.BUTTON_RIGHT,
  z: Controller.BUTTON_B, x: Controller.BUTTON_A,
  a: Controller.BUTTON_B, s: Controller.BUTTON_A,
  Enter: Controller.BUTTON_START, Shift: Controller.BUTTON_SELECT,
};

type RomResult = { name: string; base64: string };
type PickResult = { uris?: string[] };

function toolData<T>(response: { ok: boolean; result: string }): T {
  if (!response.ok || response.result.startsWith("ERROR:")) throw new Error(response.result);
  return JSON.parse(response.result) as T;
}

export function NesCard({ code }: { code: string }) {
  const title = useMemo(() => {
    try { const value = JSON.parse(code) as { title?: unknown }; return typeof value.title === "string" ? value.title : "红白机"; }
    catch { return "红白机"; }
  }, [code]);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const nesRef = useRef<NES | null>(null);
  const audioRef = useRef<AudioContext | null>(null);
  const processorRef = useRef<ScriptProcessorNode | null>(null);
  const rafRef = useRef(0);
  const romRef = useRef<{ bytes: Uint8Array; saveName: string } | null>(null);
  const keysRef = useRef(new Set<ButtonKey>());
  const soundRef = useRef({ left: new Float32Array(32768), right: new Float32Array(32768), read: 0, write: 0 });
  const [loaded, setLoaded] = useState(false);
  const [running, setRunning] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("选择 .nes 文件开始游戏");
  const [slow, setSlow] = useState(false);
  const [soundReady, setSoundReady] = useState(false);

  const stop = () => { cancelAnimationFrame(rafRef.current); rafRef.current = 0; setRunning(false); };
  const releaseKeys = () => {
    keysRef.current.forEach((key) => nesRef.current?.buttonUp(1, key));
    keysRef.current.clear();
  };
  const button = (key: ButtonKey, down: boolean) => {
    if (!nesRef.current) return;
    if (down && !keysRef.current.has(key)) { keysRef.current.add(key); nesRef.current.buttonDown(1, key); }
    if (!down && keysRef.current.delete(key)) nesRef.current.buttonUp(1, key);
  };

  const enableSound = async () => {
    try {
      if (!audioRef.current) {
        const audio = new AudioContext({ sampleRate: 44100 });
        const node = audio.createScriptProcessor(4096, 0, 2);
        node.onaudioprocess = (event) => {
          const ring = soundRef.current;
          const left = event.outputBuffer.getChannelData(0);
          const right = event.outputBuffer.getChannelData(1);
          for (let i = 0; i < left.length; i++) {
            if (ring.read === ring.write) { left[i] = 0; right[i] = 0; }
            else { left[i] = ring.left[ring.read]; right[i] = ring.right[ring.read]; ring.read = (ring.read + 1) % ring.left.length; }
          }
        };
        node.connect(audio.destination);
        audioRef.current = audio;
        processorRef.current = node;
      }
      await audioRef.current.resume();
      setSoundReady(audioRef.current.state === "running");
    } catch { setSoundReady(false); }
  };

  const startLoop = () => {
    if (!nesRef.current || rafRef.current) return;
    let last = performance.now();
    let count = 0;
    let windowStart = last;
    const tick = (now: number) => {
      rafRef.current = requestAnimationFrame(tick);
      const elapsed = now - last;
      if (elapsed < 1000 / 60) return;
      last = now - (elapsed % (1000 / 60));
      try { nesRef.current?.frame(); }
      catch (error) { stop(); setStatus(`游戏运行失败：${String(error)}`); return; }
      count++;
      if (now - windowStart >= 2500) {
        setSlow(count / ((now - windowStart) / 1000) < 40);
        count = 0; windowStart = now;
      }
    };
    rafRef.current = requestAnimationFrame(tick);
    setRunning(true);
    setStatus("游戏运行中");
  };

  const pickRom = async () => {
    stop(); releaseKeys(); setBusy(true); setStatus("正在选择 ROM…");
    try {
      await enableSound();
      const picked = toolData<PickResult>(await execTool("file_pick", { mime: "application/octet-stream" }));
      const uri = picked.uris?.[0];
      if (!uri) { setStatus("未选择文件"); return; }
      const rom = toolData<RomResult>(await execTool("file_read_b64", { uri }));
      const bytes = decodeNesRom(rom.name, rom.base64);
      const canvas = canvasRef.current;
      const context = canvas?.getContext("2d");
      if (!canvas || !context) throw new Error("画布不可用");
      const image = context.createImageData(256, 240);
      const pixels = new Uint32Array(image.data.buffer);
      const nes = new NES({
        sampleRate: audioRef.current?.sampleRate ?? 44100,
        onFrame: (frame) => {
          for (let i = 0; i < frame.length; i++) pixels[i] = 0xff000000 | frame[i];
          context.putImageData(image, 0, 0);
        },
        onAudioSample: (left, right) => {
          const ring = soundRef.current;
          const next = (ring.write + 1) % ring.left.length;
          if (next === ring.read) ring.read = (ring.read + 1) % ring.left.length;
          ring.left[ring.write] = left; ring.right[ring.write] = right; ring.write = next;
        },
      });
      nes.loadROM(bytes);
      nesRef.current = nes;
      romRef.current = { bytes, saveName: romSaveName(bytes) };
      setLoaded(true); setSlow(false);
      startLoop();
    } catch (error) { setStatus(String(error)); }
    finally { setBusy(false); }
  };

  const save = async () => {
    const nes = nesRef.current, rom = romRef.current;
    if (!nes || !rom) return;
    setBusy(true);
    try {
      const result = toolData<{ saved: boolean }>(await execTool("file_write", {
        dir: "app_private", name: rom.saveName, content: serializeNesState(rom.saveName, nes.toJSON()),
      }));
      if (!result.saved) throw new Error("保存失败");
      setStatus("存档已保存");
    } catch (error) { setStatus(`存档失败：${String(error)}`); }
    finally { setBusy(false); }
  };
  const load = async () => {
    const nes = nesRef.current, rom = romRef.current;
    if (!nes || !rom) return;
    setBusy(true);
    try {
      const data = toolData<{ content: string; truncated: boolean }>(await execTool("file_read", { dir: "app_private", name: rom.saveName }));
      if (data.truncated) throw new Error("存档超出读取上限");
      nes.fromJSON(parseNesState(data.content, rom.saveName));
      setStatus("读档成功");
    } catch (error) { setStatus(`读档失败：${String(error)}`); }
    finally { setBusy(false); }
  };

  useEffect(() => {
    const keyDown = (event: KeyboardEvent) => {
      if (!nesRef.current || !running || (event.target instanceof HTMLElement && /INPUT|TEXTAREA/.test(event.target.tagName))) return;
      const key = KEYS[event.key] ?? KEYS[event.key.toLowerCase()];
      if (key !== undefined) { event.preventDefault(); button(key, true); }
    };
    const keyUp = (event: KeyboardEvent) => {
      const key = KEYS[event.key] ?? KEYS[event.key.toLowerCase()];
      if (key !== undefined && nesRef.current) { event.preventDefault(); button(key, false); }
    };
    window.addEventListener("keydown", keyDown); window.addEventListener("keyup", keyUp);
    window.addEventListener("blur", releaseKeys);
    return () => { window.removeEventListener("keydown", keyDown); window.removeEventListener("keyup", keyUp); window.removeEventListener("blur", releaseKeys); };
  }, [running]);
  useEffect(() => () => {
    cancelAnimationFrame(rafRef.current);
    processorRef.current?.disconnect();
    void audioRef.current?.close();
    releaseKeys();
  }, []);

  const control = (label: string, key: ButtonKey, extra = "") => <button
    type="button" className={`nes-key ${extra}`} aria-label={label} disabled={!loaded}
    onPointerDown={(event) => { event.preventDefault(); event.currentTarget.setPointerCapture(event.pointerId); button(key, true); }}
    onPointerUp={(event) => { event.preventDefault(); button(key, false); }}
    onPointerCancel={() => button(key, false)} onLostPointerCapture={() => button(key, false)}
  >{label}</button>;

  return <div className={`nes-card${fullscreen ? " fullscreen" : ""}`}>
    <div className="webview-bar"><span className="webview-uri">🎮 {title}</span><button type="button" onClick={() => setFullscreen((value) => !value)}>{fullscreen ? "退出全屏" : "全屏"}</button></div>
    <canvas ref={canvasRef} width={256} height={240} aria-label="NES 游戏画面" />
    <div className="nes-actions">
      <button type="button" disabled={busy} onClick={pickRom}>{loaded ? "换游戏" : "选择 .nes 文件"}</button>
      <button type="button" disabled={!loaded || busy} onClick={() => { if (running) { stop(); releaseKeys(); setStatus("已暂停"); } else { void enableSound(); startLoop(); } }}>{running ? "暂停" : "开始"}</button>
      <button type="button" disabled={!loaded || busy} onClick={() => { nesRef.current?.reset(); soundRef.current.read = soundRef.current.write; setStatus("已重置"); }}>重置</button>
      <button type="button" disabled={!loaded || busy} onClick={save}>保存状态</button>
      <button type="button" disabled={!loaded || busy} onClick={load}>读档</button>
    </div>
    <div className="nes-status" role="status">{status}{loaded && !soundReady ? " · 点击开始启用声音" : ""}{slow ? " · 帧率较低，已按设备速度运行" : ""}</div>
    <div className="nes-controls">
      <div className="nes-dpad">{control("↑", Controller.BUTTON_UP, "up")}{control("←", Controller.BUTTON_LEFT, "left")}{control("→", Controller.BUTTON_RIGHT, "right")}{control("↓", Controller.BUTTON_DOWN, "down")}</div>
      <div className="nes-middle">{control("SELECT", Controller.BUTTON_SELECT)}{control("START", Controller.BUTTON_START)}</div>
      <div className="nes-ab">{control("B", Controller.BUTTON_B)}{control("A", Controller.BUTTON_A)}</div>
    </div>
    <div className="nes-help">键盘：方向键、Z/B、X/A、Enter 开始、Shift 选择</div>
  </div>;
}
