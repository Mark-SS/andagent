/**
 * Kotlin bridge access: settings, tools, and the LLM HTTP transport.
 *
 * The WebView cannot call the LLM endpoint directly (CORS: the API server does
 * not send Access-Control-Allow-Origin). Every LLM request is therefore proxied
 * through Kotlin/OkHttp: we replace globalThis.fetch with a thin adapter that
 * turns the OpenAI SDK's fetch call into
 *   AndroidBridge.llmFetchStart(reqId, url, headersJson, bodyJson)
 * and feeds the streamed SSE lines back into a ReadableStream.
 */

export interface Settings {
  baseUrl: string;
  apiKey: string;
  model: string;
  systemPrompt: string;
}

export const DEFAULT_SYSTEM_PROMPT = [
  "你是 AndAgent，一个运行在 Android 手机上的智能助手，可以直接操作这台设备。",
  "回答使用简体中文，简洁、直接、可执行；需要数据时先调用工具，绝不编造工具返回里没有的数字或状态。",
  "",
  "## 身份与语气",
  "- 默认简体中文；用户切换语言时跟随。",
  "- 回答先给结论，再给关键数据；避免堆砌原始 JSON，超过 3 组数据建议用 ```chart 图表呈现。",
  "- 不确定就说不确定；工具报错时如实转述错误原因，不要掩饰。",
  "",
  "## 能力总览（按用户意图选择工具）",
  "- 设备状态：device_info / battery_detail / sensor_list / sensor_read / location_get / location_status / steps_today / steps_history",
  "- 文件与相册：storage_scan / album_stats / file_download / file_search / file_read / file_write / file_list / file_pick / image_save / image_info / image_preview / image_edit / image_collage / open_folder",
  "- 日历与提醒：calendar_list / calendar_events / calendar_add / calendar_delete / alarm_set / timer_set",
  "- 通讯：phone_call / sms_send / clipboard_read / clipboard_write / notify_send / contact 查询暂不支持",
  "- 网络与连接：wifi_info / wifi_toggle / wifi_connect / wifi_connect_strong / wifi_saved_list / wifi_forget / bt_scan / bt_paired_list / bt_connect / bt_connect_direct / bt_toggle",
  "- 屏幕与自动化：screen_record / screen_record_stop / screen_record_list / screen_record_h264 / screen_capture / input_tap / input_swipe / input_text",
  "- 脚本与包管理：termux_setup / shell_exec / pkg_install / js_eval / script_check / json_fix（JS 脚本可用 andagent.tool 调用设备工具）",
  "- 可复用脚本：script_save 保存 → script_run 随时执行（支持 args_json 传参）→ script_list 查看；修改前用 script_read。适合周期性任务、数据变换管道、自动化流程。脚本内可用 andagent.tool() 调设备工具。",
  "- 系统导航：open_settings（27 类设置页直达）/ app_list / get_time / device_info",
  "- 桌面小组件：widget_generate 先规划 WidgetSpec v2 JSON，widget_save_and_pin 保存并请求添加；数据可绑定系统或 QuickJS 工具",
  "",
  "## 工作纪律",
  "1. 先读懂意图再选工具；一次任务里相关工具可连续调用，但每步等结果再决定下一步。",
  "2. 写入/破坏性操作（calendar_add、calendar_delete、alarm_set、file_write、file_download、image_save、sms_send、phone_call、wifi_forget、bt_toggle、wifi_toggle、input_*、pkg_install）：执行前用一句话说明将改动什么；用户已明确要求的可直接执行。",
  "3. 权限被拒时按权限纪律处理（见下），不要反复重试同一权限。",
  "4. 涉及删除、发送短信、拨打电话：除非用户明确给出对象和内容，否则先复述待执行动作等确认。",
  "",
  "## 输出呈现",
  "- 工具返回的 JSON：提炼要点用文字或表格表达；用户要看对比/趋势/占比时用 chart 块。",
  "- 屏幕截图（screen_capture）的缩略图会在工具卡片里展示给用户；你只能拿到路径等文字信息，不要假装看到了画面，也不要复制图片数据。",
  "- 图片编辑用 image_edit 的 ops_json 顺序描述裁剪、旋转、色彩等操作；拼图用 image_collage。可先 image_preview 看效果，再调参数；上次 savedPath 可作为下一次 source。",
  "- 图片/相册的缩略图都由工具卡片自动渲染；不要输出 ```image / ```photos 围栏，也不要粘贴 base64 或 previewFence，只用文字说明结果、数量、savedPath 或 uri。",
  "- 需要拍照时输出 ```camera 围栏（如 {} 或 {\"facingMode\":\"environment\"}），用户会看到实时取景并点击拍照，照片自动存入相册并把路径回传对话。",
  "- 生成小组件后，把 WidgetSpec JSON 放入 ```widget 围栏让用户在对话内预览效果，确认后再 widget_save_and_pin。",
  "- 位置结果用 ```geo 块，内容为 {\"lat\":数字,\"lng\":数字,\"label\":字符串} 的 JSON；键值状态用 ```kv 块，内容为 JSON 对象或每行 label: value。",
  "- 相册结果用文字或表格列出文件名；长输出（shell_exec、file_read）给出摘要 + 关键行，不整段粘贴。",
].join("\n");

/** No endpoint, model ID or API key ships in the bundle: they come from
 *  Download/config.json through the native bridge (or from Settings). */
export const DEFAULT_SETTINGS: Settings = {
  baseUrl: "",
  apiKey: "",
  model: "",
  systemPrompt: DEFAULT_SYSTEM_PROMPT,
};

const DEV_SETTINGS_KEY = "andagent.settings.dev.v1";

/** Payload of an `andagent://` deep link (from an external app or a widget). */
export interface DeeplinkDetail {
  uri: string;
  prompt: string;
  auto: boolean;
}

interface AndroidBridge {
  getSettings(): string;
  consumeDeeplink(): string;
  openEmbeddedWebview(uri: string): string;
  dbGuiOpen(name: string): string;
  saveSettings(json: string): void;
  getAppVersion(): string;
  widgetList(): string;
  widgetSave(specJson: string, id: string): string;
  widgetPin(id: string): string;
  widgetRefresh(id: string): string;
  widgetDelete(id: string): string;
  llmFetchStart(id: string, url: string, headersJson: string, body: string): void;
  llmCancel(id: string): void;
  toolExec(id: string, name: string, argsJson: string): void;
  transcribeAudio(id: string, filePath: string, language: string): void;
  saveAudioBase64(id: string, base64: string): string;
  requestPermissions(namesJson: string): void;
  permissionStatus(names: string): string;
  openAppSettings(): void;
  termuxStatus(): string;
  termuxGrantViaShizuku(): string;
  exportConversation(title: string, markdown: string): string;
  shizukuStatus(): string;
  shizukuRequest(): string;
  healthPermissionRequest(): string;
}

declare global {
  interface Window {
    AndroidBridge?: AndroidBridge;
    __andagent?: { receive: (payload: string) => void };
  }
}

function bridge(): AndroidBridge | undefined {
  return window.AndroidBridge;
}

export function hasBridge(): boolean {
  return !!bridge();
}

/* ------------------------------------------------------------------ */
/* Settings                                                            */
/* ------------------------------------------------------------------ */

export function loadSettings(): Settings {
  const b = bridge();
  if (!b) {
    try {
      const raw = localStorage.getItem(DEV_SETTINGS_KEY);
      if (raw) return { ...DEFAULT_SETTINGS, ...JSON.parse(raw) };
    } catch {
      /* ignore */
    }
    return { ...DEFAULT_SETTINGS };
  }
  try {
    const parsed = JSON.parse(b.getSettings()) as Partial<Settings>;
    return { ...DEFAULT_SETTINGS, ...parsed };
  } catch (e) {
    console.warn("loadSettings failed", e);
    return { ...DEFAULT_SETTINGS };
  }
}

export function persistSettings(settings: Settings): void {
  const b = bridge();
  if (b) {
    b.saveSettings(JSON.stringify(settings));
  } else {
    localStorage.setItem(DEV_SETTINGS_KEY, JSON.stringify(settings));
  }
}

/* ------------------------------------------------------------------ */
/* Tool execution                                                       */
/* ------------------------------------------------------------------ */

type ToolPending = {
  resolve: (result: { ok: boolean; result: string }) => void;
  timer: number;
};

const toolPending = new Map<string, ToolPending>();
let seq = 0;

/**
 * Execute a device tool in Kotlin. The bridge call is async; the result arrives
 * through window.__andagent.receive({type:"tool.result", ...}).
 */
export function execTool(name: string, args: unknown): Promise<{ ok: boolean; result: string }> {
  if (name === "widget_generate") {
    return Promise.resolve({ ok: true, result: "请根据 spec_description 生成符合 WidgetSpec v2 的完整 JSON，然后调用 widget_save_and_pin({spec_json: JSON.stringify(spec)})。" });
  }
  const b = bridge();
  if (!b) {
    return Promise.resolve({
      ok: false,
      result: "ERROR: 设备桥不可用（当前在浏览器中运行，工具需要在 App 内执行）",
    });
  }
  const id = `tool-${Date.now()}-${++seq}`;
  return new Promise((resolve) => {
    const timer = window.setTimeout(() => {
      toolPending.delete(id);
      resolve({ ok: false, result: "ERROR: 工具执行超时（60s）" });
    }, 65_000);
    toolPending.set(id, { resolve, timer });
    try {
      b.toolExec(id, name, JSON.stringify(args ?? {}));
    } catch (e) {
      window.clearTimeout(timer);
      toolPending.delete(id);
      resolve({ ok: false, result: `ERROR: 无法调用工具桥：${String(e)}` });
    }
  });
}

/**
 * Server-side transcription of a recorded audio file via the native bridge
 * (OpenAI-compatible /audio/transcriptions on the configured baseUrl).
 */
export function transcribeAudio(
  filePath: string,
  language = "zh",
): Promise<{ ok: boolean; result: string }> {
  const b = bridge();
  if (!b) {
    return Promise.resolve({ ok: false, result: "ERROR: 语音转写仅能在 App 内使用" });
  }
  const id = `asr-${Date.now()}-${++seq}`;
  return new Promise((resolve) => {
    const timer = window.setTimeout(() => {
      toolPending.delete(id);
      resolve({ ok: false, result: "ERROR: 转写超时（120s）" });
    }, 120_000);
    toolPending.set(id, { resolve, timer });
    try {
      b.transcribeAudio(id, filePath, language);
    } catch (e) {
      window.clearTimeout(timer);
      toolPending.delete(id);
      resolve({ ok: false, result: `ERROR: 无法调用转写桥：${String(e)}` });
    }
  });
}

/* ------------------------------------------------------------------ */
/* Runtime permissions (on-demand from JS)                             */
/* ------------------------------------------------------------------ */

type PermissionPending = {
  resolve: (granted: Record<string, boolean>) => void;
  timer: number;
};

let permissionPending: PermissionPending | null = null;

/**
 * Ask Android for runtime permissions. Resolves with the per-permission grant
 * state; tools still re-check the permission themselves, so a denial here is
 * never fatal.
 */
export function requestPermissions(names: string[]): Promise<Record<string, boolean>> {
  const b = bridge();
  if (!b) return Promise.resolve({});
  return new Promise((resolve) => {
    if (permissionPending) {
      window.clearTimeout(permissionPending.timer);
      permissionPending.resolve({});
      permissionPending = null;
    }
    const timer = window.setTimeout(() => {
      permissionPending = null;
      resolve({});
    }, 60_000);
    permissionPending = { resolve, timer };
    try {
      b.requestPermissions(JSON.stringify(names));
    } catch {
      window.clearTimeout(timer);
      permissionPending = null;
      resolve({});
    }
  });
}

/* ------------------------------------------------------------------ */
/* Shizuku / Health Connect                                            */
/* ------------------------------------------------------------------ */

export type ShizukuState = "granted" | "not_granted" | "unavailable";

export interface ShizukuStatus {
  status: ShizukuState;
  installed?: boolean;
  managerPackage?: string;
  managerVersion?: string;
  note?: string;
  lastRequestResult?: string;
  lastRequestError?: string;
}

export interface PermissionRequestAck {
  requested: boolean;
  message?: string;
}

export function getShizukuStatus(): ShizukuStatus {
  const b = bridge();
  if (!b) {
    return {
      status: "unavailable",
      installed: false,
      note: "浏览器预览模式：Shizuku 仅能在 App 内使用",
    };
  }
  try {
    return JSON.parse(b.shizukuStatus()) as ShizukuStatus;
  } catch (e) {
    return { status: "unavailable", note: `读取 Shizuku 状态失败：${String(e)}` };
  }
}

export function requestShizukuPermission(): PermissionRequestAck {
  const b = bridge();
  if (!b) return { requested: false, message: "浏览器预览模式：Shizuku 仅能在 App 内使用" };
  try {
    return JSON.parse(b.shizukuRequest()) as PermissionRequestAck;
  } catch (e) {
    return { requested: false, message: `发起 Shizuku 授权失败：${String(e)}` };
  }
}

export function requestHealthPermission(): PermissionRequestAck {
  const b = bridge();
  if (!b) return { requested: false, message: "浏览器预览模式：健康数据仅能在 App 内使用" };
  try {
    return JSON.parse(b.healthPermissionRequest()) as PermissionRequestAck;
  } catch (e) {
    return { requested: false, message: `发起健康数据授权失败：${String(e)}` };
  }
}

/* ------------------------------------------------------------------ */
/* LLM fetch transport                                                  */
/* ------------------------------------------------------------------ */

type LlmPending = {
  stream: ReadableStream<Uint8Array>;
  controller: ReadableStreamDefaultController<Uint8Array> | null;
  encoder: TextEncoder;
  resolve: (response: Response) => void;
  reject: (error: unknown) => void;
  settled: boolean;
  finished: boolean;
  cleaned: boolean;
  signal?: AbortSignal;
  onAbort?: () => void;
};

const llmPending = new Map<string, LlmPending>();
let configuredBaseUrl = "";

function normalizeBaseUrl(url: string): string {
  return url.trim().replace(/\/+$/, "");
}

function cleanupLlm(id: string, entry: LlmPending | undefined): void {
  const p = entry ?? llmPending.get(id);
  if (!p) return;
  llmPending.delete(id);
  if (p.cleaned) return;
  p.cleaned = true;
  if (p.onAbort && p.signal) p.signal.removeEventListener("abort", p.onAbort);
}

/**
 * Replace globalThis.fetch once. Only requests to the configured LLM base URL
 * are proxied; everything else (assets, dev HMR) keeps the native fetch.
 */
export function installLLMFetch(baseUrl: string): void {
  configuredBaseUrl = normalizeBaseUrl(baseUrl);
  const w = window as typeof window & { __andagentFetchInstalled?: boolean };
  if (w.__andagentFetchInstalled) return;
  w.__andagentFetchInstalled = true;
  const nativeFetch = typeof window.fetch === "function" ? window.fetch.bind(window) : null;
  if (!nativeFetch) return;
  window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
    const url =
      typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (bridge() && configuredBaseUrl && url.startsWith(configuredBaseUrl)) {
      return bridgeFetch(input as RequestInfo, init);
    }
    return nativeFetch(input as RequestInfo, init);
  }) as typeof window.fetch;
}

async function bridgeFetch(input: RequestInfo, init?: RequestInit): Promise<Response> {
  const request = input instanceof Request ? input : null;
  const url = request ? request.url : String(input);
  const method = init?.method ?? request?.method ?? "POST";

  const headers: Record<string, string> = {};
  request?.headers.forEach((value, key) => (headers[key] = value));
  if (init?.headers) {
    new Headers(init.headers).forEach((value, key) => (headers[key] = value));
  }

  let body = init?.body;
  if (body == null && request) {
    body = await request.clone().text();
  }
  const bodyText = typeof body === "string" ? body : body == null ? "" : String(body);
  const signal = init?.signal ?? request?.signal ?? undefined;

  if (signal?.aborted) {
    throw new DOMException("Aborted", "AbortError");
  }

  const id = `llm-${Date.now()}-${++seq}`;
  const encoder = new TextEncoder();
  let entry!: LlmPending;
  let controllerRef: ReadableStreamDefaultController<Uint8Array> | null = null;

  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controllerRef = controller;
    },
    cancel() {
      bridge()?.llmCancel(id);
      cleanupLlm(id, entry);
    },
  });

  return new Promise<Response>((resolve, reject) => {
    entry = {
      stream,
      controller: controllerRef,
      encoder,
      resolve,
      reject,
      settled: false,
      finished: false,
      cleaned: false,
      signal,
    };
    entry.onAbort = () => {
      if (entry.finished) return;
      entry.finished = true;
      bridge()?.llmCancel(id);
      try {
        entry.controller?.error(new DOMException("Aborted", "AbortError"));
      } catch {
        /* stream already closed */
      }
      if (!entry.settled) reject(new DOMException("Aborted", "AbortError"));
      cleanupLlm(id, entry);
    };
    signal?.addEventListener("abort", entry.onAbort);
    llmPending.set(id, entry);

    try {
      bridge()!.llmFetchStart(id, url, JSON.stringify(headers), bodyText);
    } catch (e) {
      cleanupLlm(id, entry);
      reject(e);
    }
  });
}

function handleEvent(payload: string): void {
  let event: Record<string, unknown>;
  try {
    event = JSON.parse(payload);
  } catch (e) {
    console.warn("bad bridge event", e, payload.slice(0, 200));
    return;
  }
  const type = event.type as string;

  if (type === "notice") {
    window.dispatchEvent(new CustomEvent("andagent:notice", { detail: String(event.message ?? "") }));
    return;
  }

  if (type === "deeplink.flush") {
    // Native side only signals "there is a queued link"; the payload is pulled
    // via consumeDeeplink() so early events cannot be lost before mount.
    window.dispatchEvent(new Event("andagent:deeplink-flush"));
    return;
  }

  if (type === "tool.result") {
    const id = event.id as string;
    const pending = toolPending.get(id);
    if (!pending) return;
    window.clearTimeout(pending.timer);
    toolPending.delete(id);
    pending.resolve({
      ok: !!event.ok,
      result: typeof event.result === "string" ? event.result : String(event.result ?? ""),
    });
    return;
  }

  if (type === "permission.result") {
    const pending = permissionPending;
    if (!pending) return;
    window.clearTimeout(pending.timer);
    permissionPending = null;
    pending.resolve((event.granted as Record<string, boolean> | undefined) ?? {});
    return;
  }

  if (type === "conversation.exported") {
    window.dispatchEvent(new CustomEvent("andagent:conversation-exported", { detail: String(event.path ?? "") }));
    return;
  }

  const id = event.id as string;
  const entry = llmPending.get(id);

  switch (type) {
    case "llm.headers": {
      if (!entry || entry.settled) return;
      entry.settled = true;
      const status = Number(event.status ?? 200);
      const safeStatus = status >= 200 && status <= 599 ? status : 200;
      const headers = new Headers(
        (event.headers as Record<string, string> | undefined) ?? {},
      );
      try {
        entry.resolve(
          new Response(entry.stream, {
            status: safeStatus,
            statusText: String(event.statusText ?? ""),
            headers,
          }),
        );
      } catch (e) {
        entry.reject(e);
      }
      return;
    }
    case "llm.chunk": {
      if (!entry || entry.finished || !entry.controller) return;
      try {
        entry.controller.enqueue(entry.encoder.encode(String(event.line ?? "") + "\n"));
      } catch {
        /* stream cancelled by the SDK */
      }
      return;
    }
    case "llm.chunks": {
      if (!entry || entry.finished || !entry.controller) return;
      const lines = (event.lines as string[] | undefined) ?? [];
      try {
        for (const line of lines) {
          entry.controller.enqueue(entry.encoder.encode(line + "\n"));
        }
      } catch {
        /* stream cancelled */
      }
      return;
    }
    case "llm.done": {
      if (!entry) return;
      entry.finished = true;
      try {
        entry.controller?.close();
      } catch {
        /* ignore */
      }
      if (!entry.settled) {
        entry.reject(new Error("LLM 连接在响应头之前结束"));
      }
      cleanupLlm(id, entry);
      return;
    }
    case "llm.error": {
      if (!entry) return;
      entry.finished = true;
      const message = String(event.message ?? "LLM 请求失败");
      try {
        entry.controller?.error(new Error(message));
      } catch {
        /* ignore */
      }
      if (!entry.settled) entry.reject(new Error(message));
      cleanupLlm(id, entry);
      return;
    }
  }
}

// Install the callback as early as possible — Kotlin may push events while the
// React tree is still mounting (e.g. a retry right after launch).
window.__andagent = { receive: handleEvent };

/* ------------------------------------------------------------------ */
/* App version (from BuildConfig via the bridge; dev fallback)          */
/* ------------------------------------------------------------------ */

export function getAppVersion(): string {
  const b = bridge();
  if (!b) return "dev";
  try {
    const parsed = JSON.parse(b.getAppVersion()) as { version?: string };
    return parsed.version || "dev";
  } catch {
    return "dev";
  }
}

/** Promptless per-permission state: "granted"|"denied"|"permanently_denied". */
export function getPermissionStatus(names: string[]): Record<string, string> {
  const b = bridge();
  if (!b) return {};
  try {
    return JSON.parse(b.permissionStatus(JSON.stringify(names))) as Record<string, string>;
  } catch {
    return {};
  }
}

export function openAppSettings(): void {
  bridge()?.openAppSettings();
}

/* ------------------------------------------------------------------ */
/* Voice input: decode recorded blob → WAV → native file → transcribe   */
/* ------------------------------------------------------------------ */

/** Decode any browser-recorded audio blob to 16kHz mono PCM WAV. */
async function blobToWavBlob(blob: Blob): Promise<Blob> {
  const AudioCtx =
    window.AudioContext ??
    (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
  const ctx = new AudioCtx();
  try {
    const buf = await blob.arrayBuffer();
    const audio = await ctx.decodeAudioData(buf);
    // Downmix to mono 16kHz.
    const targetRate = 16000;
    const frames = Math.ceil(audio.duration * targetRate);
    const offline = new OfflineAudioContext(1, frames, targetRate);
    const src = offline.createBufferSource();
    src.buffer = audio;
    src.connect(offline.destination);
    src.start();
    const rendered = await offline.startRendering();
    const pcm = rendered.getChannelData(0);
    // 16-bit PCM WAV header + data.
    const dataLen = pcm.length * 2;
    const out = new ArrayBuffer(44 + dataLen);
    const view = new DataView(out);
    const w = (off: number, s: string) => {
      for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i));
    };
    w(0, "RIFF");
    view.setUint32(4, 36 + dataLen, true);
    w(8, "WAVE");
    w(12, "fmt ");
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true); // PCM
    view.setUint16(22, 1, true); // mono
    view.setUint32(24, targetRate, true);
    view.setUint32(28, targetRate * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    w(36, "data");
    view.setUint32(40, dataLen, true);
    for (let i = 0; i < pcm.length; i++) {
      const s = Math.max(-1, Math.min(1, pcm[i]));
      view.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    }
    return new Blob([out], { type: "audio/wav" });
  } finally {
    void ctx.close();
  }
}

/** Save a blob to the native filesystem (base64 through the bridge). */
export async function saveAudioBlob(blob: Blob): Promise<string> {
  const b = bridge();
  if (!b) throw new Error("语音输入仅能在 App 内使用");
  const wav = await blobToWavBlob(blob);
  const base64 = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const s = String(reader.result);
      resolve(s.slice(s.indexOf(",") + 1));
    };
    reader.onerror = () => reject(new Error("读取录音失败"));
    reader.readAsDataURL(wav);
  });
  const id = `wav-${Date.now()}-${++seq}`;
  const raw = b.saveAudioBase64(id, base64);
  const parsed = JSON.parse(raw) as { ok: boolean; path?: string; message?: string };
  if (!parsed.ok || !parsed.path) throw new Error(parsed.message ?? "保存音频失败");
  return parsed.path;
}

/** Test hook for the Node-side transport harness. */
export const __internal = { handleEvent, bridgeFetch };

export function getTermuxStatus(): string { try { return bridge()?.termuxStatus() ?? "none"; } catch { return "none"; } }
export function grantTermuxViaShizuku(): string {
  try { return bridge()?.termuxGrantViaShizuku() ?? "ERROR: Android 桥不可用"; }
  catch (e) { return `ERROR: Shizuku 代授权失败：${String(e)}`; }
}
export function exportConversation(title: string, markdown: string): string {
  try { return bridge()?.exportConversation(title, markdown) ?? "ERROR: Android 桥不可用"; }
  catch (e) { return `ERROR: 导出失败：${String(e)}`; }
}

export type SavedWidget = { id: string; name: string; specJson: string };
export function widgetList(): SavedWidget[] {
  try { return JSON.parse(bridge()?.widgetList() ?? "[]") as SavedWidget[]; } catch { return []; }
}
export function widgetSave(specJson: string, id = ""): string { return bridge()?.widgetSave(specJson, id) ?? "ERROR: 设备桥不可用"; }
export function widgetPin(id: string): string { return bridge()?.widgetPin(id) ?? "ERROR: 设备桥不可用"; }
export function widgetRefresh(id: string): string { return bridge()?.widgetRefresh(id) ?? "ERROR: 设备桥不可用"; }
export function widgetDelete(id: string): string { return bridge()?.widgetDelete(id) ?? "ERROR: 设备桥不可用"; }

/** Pull deep links queued natively (covers links arriving before mount). */
export function consumeDeeplink(): DeeplinkDetail[] {
  try {
    const raw = bridge()?.consumeDeeplink?.() ?? "[]";
    const parsed: unknown = JSON.parse(raw);
    const list = Array.isArray(parsed) ? parsed : [parsed];
    return (list as DeeplinkDetail[]).filter((d) => d && typeof d.prompt === "string" && d.prompt);
  } catch {
    return [];
  }
}

export function openEmbeddedWebview(uri: string): string {
  try { const result = bridge()?.openEmbeddedWebview(uri) ?? "ERROR: 设备桥不可用"; return result.startsWith("ERROR:") ? result : ""; }
  catch (e) { return `ERROR: ${String(e)}`; }
}
