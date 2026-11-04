import type {
  AssistantMessage,
  AssistantMessageEvent,
  Message as PiMessage,
  Model,
  Tool as PiTool,
  ToolCall,
  ToolResultMessage,
  UserMessage,
} from "@mariozechner/pi-ai";
import type { ThreadMessageLike } from "@assistant-ui/react";
import { execTool, type Settings } from "./bridge";

type PiModule = typeof import("@mariozechner/pi-ai");

const MESSAGES_KEY = "andagent.messages.v1";
const MAX_TOOL_ROUNDS = 8;

let piPromise: Promise<PiModule> | null = null;

/** Load the esbuild-produced pi bundle from the app's asset root. */
export function loadPi(): Promise<PiModule> {
  if (!piPromise) {
    piPromise = (async () => {
      const url = new URL("pi-bundle.js", document.baseURI).href;
      const mod = (await import(/* @vite-ignore */ url)) as PiModule;
      mod.registerBuiltInApiProviders();
      return mod;
    })();
  }
  return piPromise;
}

const TOOL_DEFS = [
  {
    name: "get_time",
    description:
      "获取当前日期、时间、时区与星期。用户问现在几点、今天几号、今天星期几时使用。",
    parameters: {
      type: "object",
      properties: {
        timezone: {
          type: "string",
          description: "IANA 时区，例如 Asia/Shanghai。省略则使用设备本地时区。",
        },
      },
      required: [],
    },
  },
  {
    name: "device_info",
    description: "获取设备信息：型号、Android 版本、存储空间、内存、电量。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "storage_scan",
    description:
      "分析存储占用：剩余/总空间、下载/影片/音乐/文档目录下最大的文件、应用缓存大小。",
    parameters: {
      type: "object",
      properties: {
        top_n: { type: "integer", description: "每个目录列出最大的 N 个文件，默认 10" },
        path: { type: "string", description: "可选：只分析指定目录" },
      },
      required: [],
    },
  },
  {
    name: "album_stats",
    description: "相册快速统计：截图数量与体积、按年份的照片数量与体积。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "calendar_list",
    description: "列出设备上的日历账户（id、名称、所属账户、是否可写）。写入事件前可先用它选择 calendar_id。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "calendar_events",
    description:
      "查询日历事件：按时间窗 start/end 过滤，返回标题、开始/结束时间、地点、所属日历。最多 100 条。",
    parameters: {
      type: "object",
      properties: {
        start: { type: "string", description: "时间窗开始，例如 2026-11-01 或 2026-11-01 08:00；默认 30 天前" },
        end: { type: "string", description: "时间窗结束，格式同上；默认 30 天后" },
        calendar_id: { type: "integer", description: "可选：只看某个日历 id" },
      },
      required: [],
    },
  },
  {
    name: "calendar_add",
    description:
      "创建日历事件（会修改设备数据）：title/begin/end 必填，location/description/remindMinutes 可选。",
    parameters: {
      type: "object",
      properties: {
        title: { type: "string", description: "事件标题" },
        begin: { type: "string", description: "开始时间，例如 2026-11-05 14:00" },
        end: { type: "string", description: "结束时间，格式同上" },
        location: { type: "string", description: "可选：地点" },
        description: { type: "string", description: "可选：备注" },
        remindMinutes: { type: "integer", description: "可选：提前多少分钟提醒" },
        calendar_id: { type: "integer", description: "可选：目标日历 id，默认第一个可写日历" },
      },
      required: ["title", "begin", "end"],
    },
  },
  {
    name: "calendar_delete",
    description: "按事件 id 删除日历事件（会修改设备数据）。先用 calendar_events 查询 id。",
    parameters: {
      type: "object",
      properties: {
        id: { type: "integer", description: "要删除的事件 id" },
      },
      required: ["id"],
    },
  },
  {
    name: "alarm_set",
    description: "唤起系统闹钟应用设置闹钟（小时/分钟/备注），由用户在系统界面确认。",
    parameters: {
      type: "object",
      properties: {
        hour: { type: "integer", description: "小时，0-23" },
        minutes: { type: "integer", description: "分钟，0-59" },
        message: { type: "string", description: "可选：闹钟备注" },
      },
      required: ["hour", "minutes"],
    },
  },
  {
    name: "timer_set",
    description: "唤起系统时钟应用设置倒计时（秒），由用户在系统界面确认。",
    parameters: {
      type: "object",
      properties: {
        seconds: { type: "integer", description: "倒计时秒数" },
        message: { type: "string", description: "可选：计时器备注" },
      },
      required: ["seconds"],
    },
  },
  {
    name: "battery_detail",
    description: "读取电池详情：电量百分比、充电状态、供电方式、健康度、温度、电压。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "wifi_info",
    description:
      "读取 Wi-Fi 信息：SSID、信号强度、IP、链路速度。定位权限被拒时会降级为网络类型。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "clipboard_write",
    description: "把文本写入系统剪贴板，方便用户粘贴到其他应用。",
    parameters: {
      type: "object",
      properties: {
        text: { type: "string", description: "要复制的文本" },
        label: { type: "string", description: "可选：剪贴板标签，默认 AndAgent" },
      },
      required: ["text"],
    },
  },
  {
    name: "clipboard_read",
    description: "读取系统剪贴板文本（Android 10+ 需要应用窗口有焦点，失败会给明确原因）。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "notify_send",
    description: "发送一条本地通知（标题 + 内容）。Android 13+ 会申请通知权限。",
    parameters: {
      type: "object",
      properties: {
        title: { type: "string", description: "通知标题" },
        text: { type: "string", description: "通知内容" },
      },
      required: ["title", "text"],
    },
  },
  {
    name: "app_list",
    description: "列出已安装应用（包名、应用名、版本、是否系统应用），按安装时间倒序，最多 200 个。",
    parameters: { type: "object", properties: {}, required: [] },
  },
] satisfies PiTool[];

/**
 * Appended to whatever system prompt the user saved, so the chart protocol is
 * always in effect (stored prompts predate this feature).
 */
const CHART_GUIDE = [
  "数据可视化约定：",
  "1. 当需要展示数据对比、趋势或占比时，在回复里插入 ```chart 围栏代码块，围栏内容必须是合法的 ECharts option JSON。",
  "2. 只能使用纯 JSON：禁止函数、注释、变量、单引号或 JS 表达式（例如 formatter 函数一律不要写）。",
  "3. 图表类型仅限 bar、line、pie，用 series[].type 指定；数据标签直接用字符串/数字数组。",
  "4. 工具返回 JSON 时，先读懂数据，再把整理后的 option 放进 chart 块，不要直接粘贴大段原始 JSON。",
  "5. 示例：",
  "```chart",
  '{"tooltip":{},"legend":{},"xAxis":{"type":"category","data":["一月","二月","三月"]},"yAxis":{"type":"value"},"series":[{"name":"照片数","type":"bar","data":[12,20,8]}]}',
  "```",
].join("\n");

const ZERO_USAGE = {
  input: 0,
  output: 0,
  cacheRead: 0,
  cacheWrite: 0,
  totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

function buildModel(settings: Settings): Model<"openai-completions"> {
  return {
    id: settings.model,
    name: settings.model,
    api: "openai-completions",
    provider: "custom",
    baseUrl: settings.baseUrl.trim().replace(/\/+$/, ""),
    input: ["text"],
    reasoning: false,
    contextWindow: 128_000,
    maxTokens: 8_192,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    // GLM (and most OpenAI-compatible endpoints) wants max_tokens, not
    // max_completion_tokens; the system role, not developer.
    compat: {
      maxTokensField: "max_tokens",
      supportsStore: false,
      supportsDeveloperRole: false,
      supportsUsageInStreaming: true,
    },
  };
}

export interface ToolView {
  id: string;
  name: string;
  args: Record<string, any>;
  result?: string;
  isError?: boolean;
  running?: boolean;
}

type Draft = { text: string; tools: ToolView[] };
type UiPart = Exclude<NonNullable<ThreadMessageLike["content"]>, string>[number];

export interface AgentSnapshot {
  messages: ThreadMessageLike[];
  running: boolean;
  error: string | null;
}

function toUiMessages(messages: PiMessage[], draft: Draft | null): ThreadMessageLike[] {
  const results = new Map<string, { text: string; isError: boolean }>();
  for (const message of messages) {
    if (message.role === "toolResult") {
      const text = message.content
        .map((part) => (part.type === "text" ? part.text : "[image]"))
        .join("\n");
      results.set(message.toolCallId, { text, isError: message.isError });
    }
  }

  const ui: ThreadMessageLike[] = [];
  messages.forEach((message, index) => {
    if (message.role === "user") {
      const text =
        typeof message.content === "string"
          ? message.content
          : message.content.map((p) => (p.type === "text" ? p.text : "")).join("");
      ui.push({
        id: `u-${index}-${message.timestamp}`,
        role: "user",
        content: [{ type: "text", text }],
        createdAt: new Date(message.timestamp),
      });
      return;
    }
    if (message.role !== "assistant") return; // toolResult is folded into the call card

    const parts: UiPart[] = [];
    for (const part of message.content) {
      if (part.type === "text") {
        if (part.text) parts.push({ type: "text", text: part.text });
      } else if (part.type === "toolCall") {
        const result = results.get(part.id);
        parts.push({
          type: "tool-call",
          toolCallId: part.id,
          toolName: part.name,
          args: part.arguments ?? {},
          argsText: JSON.stringify(part.arguments ?? {}),
          result: result?.text,
          isError: result?.isError ?? false,
        });
      }
    }
    const incomplete =
      message.stopReason === "aborted" || message.stopReason === "error";
    ui.push({
      id: `a-${index}-${message.timestamp}`,
      role: "assistant",
      content: parts.length ? parts : [{ type: "text", text: "" }],
      status: incomplete
        ? {
            type: "incomplete",
            reason: message.stopReason === "aborted" ? "cancelled" : "error",
          }
        : { type: "complete", reason: "stop" },
      createdAt: new Date(message.timestamp),
    });
  });

  if (draft) {
    const parts: UiPart[] = [];
    if (draft.text) parts.push({ type: "text", text: draft.text });
    for (const tool of draft.tools) {
      parts.push({
        type: "tool-call",
        toolCallId: tool.id,
        toolName: tool.name,
        args: tool.args,
        argsText: JSON.stringify(tool.args ?? {}),
        result: tool.result,
        isError: tool.isError ?? false,
        isPreliminary: tool.running || tool.result === undefined,
      });
    }
    ui.push({
      id: "live",
      role: "assistant",
      content: parts.length ? parts : [{ type: "text", text: "" }],
      status: { type: "running" },
      createdAt: new Date(),
    });
  }
  return ui;
}

function loadPersisted(): PiMessage[] {
  try {
    const raw = localStorage.getItem(MESSAGES_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (m) => m && (m.role === "user" || m.role === "assistant" || m.role === "toolResult"),
    ) as PiMessage[];
  } catch {
    return [];
  }
}

function isAbortError(error: unknown): boolean {
  if (!error) return false;
  const name = (error as { name?: string }).name;
  const message = String((error as { message?: string }).message ?? error);
  return name === "AbortError" || /abort|cancel/i.test(message);
}

function friendlyError(error: unknown): string {
  const raw = String((error as { message?: string })?.message ?? error);
  if (/Failed to fetch|NetworkError|Load failed/i.test(raw)) {
    return `网络请求失败：${raw}。请检查 baseUrl 与网络连接。`;
  }
  return raw.length > 600 ? raw.slice(0, 600) + "…" : raw;
}

export class AgentEngine {
  private messages: PiMessage[];
  private draft: Draft | null = null;
  private running = false;
  private error: string | null = null;
  private abort: AbortController | null = null;
  private settings: Settings;
  private snapshot: AgentSnapshot;
  private listeners = new Set<() => void>();

  constructor(settings: Settings) {
    this.settings = settings;
    this.messages = loadPersisted();
    this.snapshot = { messages: toUiMessages(this.messages, null), running: false, error: null };
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): AgentSnapshot => this.snapshot;

  setSettings(settings: Settings): void {
    this.settings = settings;
  }

  clear(): void {
    this.messages = [];
    this.draft = null;
    this.error = null;
    this.persist();
    this.emit();
  }

  stop(): void {
    this.abort?.abort();
  }

  private emit(): void {
    this.snapshot = {
      messages: toUiMessages(this.messages, this.draft),
      running: this.running,
      error: this.error,
    };
    for (const listener of this.listeners) listener();
  }

  private persist(): void {
    try {
      localStorage.setItem(MESSAGES_KEY, JSON.stringify(this.messages));
    } catch (e) {
      console.warn("persist messages failed", e);
    }
  }

  async send(text: string): Promise<void> {
    const content = text.trim();
    if (!content || this.running) return;

    this.running = true;
    this.error = null;
    this.draft = null;
    const controller = new AbortController();
    this.abort = controller;
    const userMessage: UserMessage = { role: "user", content, timestamp: Date.now() };
    this.messages = [...this.messages, userMessage];
    this.persist();
    this.emit();

    let pendingText = "";
    try {
      const pi = await loadPi();
      let rounds = 0;
      let finished = false;
      while (rounds < MAX_TOOL_ROUNDS) {
        rounds++;
        const model = buildModel(this.settings);
        const stream = pi.stream(
          model,
          {
            systemPrompt: [this.settings.systemPrompt.trim(), CHART_GUIDE]
              .filter(Boolean)
              .join("\n\n"),
            messages: this.messages,
            tools: TOOL_DEFS,
          },
          {
            apiKey: this.settings.apiKey,
            baseUrl: model.baseUrl,
            signal: controller.signal,
            maxRetries: 0,
          },
        );

        let liveText = "";
        let liveTools: ToolView[] = [];
        for await (const event of stream as AsyncIterable<AssistantMessageEvent>) {
          if (event.type === "text_delta") {
            liveText += event.delta;
            pendingText = liveText;
            this.draft = { text: liveText, tools: liveTools };
            this.emit();
          } else if (event.type === "toolcall_start") {
            const partial = event.partial.content[event.contentIndex];
            if (partial?.type === "toolCall" && !liveTools.some((t) => t.id === partial.id)) {
              liveTools = [
                ...liveTools,
                { id: partial.id, name: partial.name, args: {}, running: true },
              ];
              this.draft = { text: liveText, tools: liveTools };
              this.emit();
            }
          } else if (event.type === "toolcall_end") {
            const call = event.toolCall;
            liveTools = liveTools.filter((t) => t.id !== call.id);
            liveTools.push({
              id: call.id,
              name: call.name,
              args: call.arguments ?? {},
              running: true,
            });
            this.draft = { text: liveText, tools: liveTools };
            this.emit();
          }
        }

        const final = await stream.result();
        if (controller.signal.aborted || final.stopReason === "aborted") {
          this.finishAborted(pendingText);
          return;
        }
        if (final.stopReason === "error") {
          throw new Error(final.errorMessage || "模型流式返回错误");
        }

        pendingText = "";
        this.draft = null;
        this.messages = [...this.messages, final];
        this.persist();
        this.emit();

        const toolCalls = final.content.filter((part): part is ToolCall => part.type === "toolCall");
        if (final.stopReason !== "toolUse" || toolCalls.length === 0) {
          finished = true;
          break;
        }

        for (const call of toolCalls) {
          const result = await execTool(call.name, call.arguments ?? {});
          if (controller.signal.aborted) return;
          const toolResult: ToolResultMessage = {
            role: "toolResult",
            toolCallId: call.id,
            toolName: call.name,
            content: [{ type: "text", text: result.result }],
            isError: !result.ok,
            timestamp: Date.now(),
          };
          this.messages = [...this.messages, toolResult];
          this.persist();
          this.emit();
        }
      }

      if (!finished) {
        this.error = `工具调用超过 ${MAX_TOOL_ROUNDS} 轮，已停止。可以让我继续或换个问法。`;
      }
    } catch (e) {
      if (isAbortError(e) || controller.signal.aborted) {
        this.finishAborted(pendingText);
      } else {
        console.error("agent turn failed", e);
        this.error = friendlyError(e);
      }
    } finally {
      this.running = false;
      this.draft = null;
      this.abort = null;
      this.persist();
      this.emit();
    }
  }

  private finishAborted(partialText: string): void {
    this.error = null;
    const text = partialText.trim();
    if (text) {
      const aborted: AssistantMessage = {
        role: "assistant",
        content: [{ type: "text", text }],
        api: "openai-completions",
        provider: "custom",
        model: this.settings.model,
        usage: ZERO_USAGE,
        stopReason: "aborted",
        errorMessage: "用户已停止生成",
        timestamp: Date.now(),
      };
      this.messages = [...this.messages, aborted];
    }
  }
}
