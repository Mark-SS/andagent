/**
 * A model-facing projection of the transcript.
 *
 * Several tools (screen_capture, album_photos, image_edit / image_preview /
 * image_collage) return base64 thumbnails so the UI can render them. The model
 * is text-only (`input: ["text"]`), so those payloads are pure prompt bloat:
 * they are re-sent on every tool round and stay in history for the whole
 * session. `toModelMessages` strips them from the copy handed to pi-ai while
 * `Agent.messages` keeps the originals for the UI and the session store.
 *
 * Kept structurally typed on purpose: the transcript crosses pi-ai's message
 * union, and the UI/session layers only rely on `role` + `content`.
 */
const OMITTED_VALUE = "[图片数据已省略·界面已展示]";
const OMITTED_FENCE = "[图片围栏已省略·界面已展示]";
const NOTE =
  "结果中的图片/base64 已省略，界面已自动展示；只用文字概括结果，不要输出 image/photos 围栏，也不要粘贴图片数据。";

/** Per-message text ceiling; also catches runaway shell/file output. */
const MAX_TEXT_CHARS = 12_000;
/** Whole-transcript budget for compacted messages. */
const MAX_TOTAL_CHARS = 60_000;
/** Never summarize the most recent tool results, no matter the budget. */
const KEEP_RECENT_RESULTS = 8;

const IMAGE_FENCE = /```(?:image|photos)[\s\S]*?```/g;
const DATA_IMAGE = /data:image\/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=]+/g;
const LONG_RUN = /[A-Za-z0-9+/]{256,}={0,2}/g;

const BASE64_KEYS = new Set([
  "base64",
  "thumbnailBase64",
  "sourceThumbnailBase64",
  "previewFence",
  "imageBase64",
  "contentBase64",
  "audioBase64",
]);

/** Avoid eating long prose/hex/minified tokens: real image base64 is high-entropy. */
function looksLikeBase64(run: string): boolean {
  return (
    run.length >= 512 &&
    /[a-z]/.test(run) &&
    /[A-Z]/.test(run) &&
    /[0-9]/.test(run)
  );
}

type AnyMessage = { role: string; content: unknown };
type Part = { type?: string; text?: string; arguments?: unknown; [key: string]: unknown };

function stripInline(text: string): string {
  return text
    .replace(IMAGE_FENCE, OMITTED_FENCE)
    .replace(DATA_IMAGE, OMITTED_VALUE)
    .replace(LONG_RUN, (run) => (looksLikeBase64(run) ? OMITTED_VALUE : run));
}

function clamp(text: string): string {
  if (text.length <= MAX_TEXT_CHARS) return text;
  return (
    text.slice(0, MAX_TEXT_CHARS) +
    `\n…（结果已截断：原 ${text.length} 字符；需要更多细节请用工具缩小范围重新查询）`
  );
}

function stripJson(value: unknown, key?: string): unknown {
  if (typeof value === "string") {
    return key && BASE64_KEYS.has(key) ? OMITTED_VALUE : stripInline(value);
  }
  if (Array.isArray(value)) return value.map((item) => stripJson(item));
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      out[k] = stripJson(v, k);
    }
    return out;
  }
  return value;
}

function compactText(text: string): string {
  if (text.length < 256) return text;
  const trimmed = text.trim();
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      const compacted = stripJson(JSON.parse(trimmed));
      let json = JSON.stringify(compacted);
      if (
        trimmed.length - json.length > 512 &&
        compacted &&
        typeof compacted === "object" &&
        !Array.isArray(compacted)
      ) {
        (compacted as Record<string, unknown>)._note = NOTE;
        json = JSON.stringify(compacted);
      }
      return clamp(json);
    } catch {
      /* Not JSON — fall through to plain-text stripping. */
    }
  }
  return clamp(stripInline(text));
}

function projectMessage<T extends AnyMessage>(message: T): T {
  const content: unknown = message.content;
  if (typeof content === "string") {
    return { ...message, content: compactText(content) } as unknown as T;
  }
  if (!Array.isArray(content)) return message;

  let changed = false;
  const parts = content.map((raw: unknown): unknown => {
    if (!raw || typeof raw !== "object") return raw;
    const part = raw as Part;
    if (part.type === "text" && typeof part.text === "string") {
      const text = compactText(part.text);
      if (text === part.text) return raw;
      changed = true;
      return { ...part, text };
    }
    // Tool call arguments can carry base64 too (image_save, image_edit source…).
    if (part.type === "toolCall" && part.arguments && typeof part.arguments === "object") {
      const args = JSON.stringify(stripJson(part.arguments));
      if (args === JSON.stringify(part.arguments)) return raw;
      changed = true;
      return { ...part, arguments: JSON.parse(args) };
    }
    return raw;
  });
  return changed ? ({ ...message, content: parts } as unknown as T) : message;
}

function textSize(message: AnyMessage): number {
  const content: unknown = message.content;
  if (typeof content === "string") return content.length;
  if (!Array.isArray(content)) return 0;
  let size = 0;
  for (const raw of content) {
    if (!raw || typeof raw !== "object") continue;
    const part = raw as Part;
    if (typeof part.text === "string") size += part.text.length;
    if (part.arguments) size += JSON.stringify(part.arguments).length;
  }
  return size;
}

function summarizeResult<T extends AnyMessage>(message: T): T {
  const name = (message as unknown as { toolName?: string }).toolName ?? "工具";
  return {
    ...message,
    content: [
      {
        type: "text",
        text: `（较早的 ${name} 结果已省略以控制上下文长度；如需请重新调用该工具）`,
      },
    ],
  } as unknown as T;
}

/**
 * Build the compact transcript sent to the LLM. Original messages are never
 * mutated, so UI image cards and persisted sessions keep the full base64.
 */
export function toModelMessages<T extends AnyMessage>(messages: T[]): T[] {
  const projected = messages.map(projectMessage);
  const kept: T[] = [];
  let total = 0;
  let recentResults = 0;
  for (let i = projected.length - 1; i >= 0; i--) {
    const message = projected[i];
    if (message.role === "toolResult") recentResults++;
    const size = textSize(message);
    if (
      message.role === "toolResult" &&
      recentResults > KEEP_RECENT_RESULTS &&
      total + size > MAX_TOTAL_CHARS
    ) {
      kept.push(summarizeResult(message));
      continue;
    }
    total += size;
    kept.push(message);
  }
  return kept.reverse();
}
