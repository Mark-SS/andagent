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
  "你是 AndAgent，一个运行在 Android 手机上的智能助手。",
  "回答使用简体中文，简洁、直接、可执行；需要数据时先调用工具，不要编造。",
  "你可以使用 get_time、device_info、storage_scan、album_stats 四个工具了解设备状态。",
].join("\n");

export const DEFAULT_SETTINGS: Settings = {
  baseUrl: "https://open.bigmodel.cn/api/paas/v4",
  apiKey: "",
  model: "glm-4.5-flash",
  systemPrompt: DEFAULT_SYSTEM_PROMPT,
};

const DEV_SETTINGS_KEY = "andagent.settings.dev.v1";

interface AndroidBridge {
  getSettings(): string;
  saveSettings(json: string): void;
  llmFetchStart(id: string, url: string, headersJson: string, body: string): void;
  llmCancel(id: string): void;
  toolExec(id: string, name: string, argsJson: string): void;
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
    }, 60_000);
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

/** Test hook for the Node-side transport harness. */
export const __internal = { handleEvent, bridgeFetch };
