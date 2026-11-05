/**
 * Multi-session storage for the drawer: sessions are named PiMessage[]
 * transcripts. v1 legacy key ("andagent.messages.v1") is migrated into the
 * index as the first session on first read.
 *
 * R53: when the native KV bridge is available (Android shell), sessions live
 * in the app-private KV store (`namespace=sessions`, keys `index` / `s_<id>`)
 * so clearing WebView DOM storage no longer loses conversations. Browsers
 * (preview / vitest) keep the original localStorage behavior. The one-time
 * migration copies from localStorage and deliberately leaves the source keys
 * in place (backup, cleaned up in a later pass).
 */

import { hasKvBridge, kvCall } from "./bridge";
import { sumMessageUsage } from "./usage";

export interface SessionMeta {
  id: string;
  title: string;
  updatedAt: number;
  /** R40: 会话 token 累计（input/output），保存时从助手消息 usage 求和。 */
  usage?: { input: number; output: number };
}

const INDEX_KEY = "andagent.sessions.index.v1";
const SESSION_KEY = (id: string) => `andagent.session.${id}`;
const LEGACY_KEY = "andagent.messages.v1";

/** R53: 原生 KV 命名空间与键名。 */
const KV_NS = "sessions";
const KV_INDEX_KEY = "index";
const kvSessionKey = (id: string) => (id.startsWith("s_") ? id : `s_${id}`);

// Weakly typed on purpose: values cross the localStorage serialization boundary.
type PiMessage = any; // eslint-disable-line @typescript-eslint/no-explicit-any

interface KvGetResult {
  found?: boolean;
  value?: unknown;
}

function safeParse<T>(raw: string | null): T | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

function sortIndex(list: SessionMeta[]): SessionMeta[] {
  return list.sort((a, b) => b.updatedAt - a.updatedAt);
}

/* ------------------------------- KV layer ------------------------------- */

/** `undefined` = 通道不可用/读失败，`null` = 确认不存在。 */
function kvRead(key: string): string | null | undefined {
  const res = kvCall<KvGetResult>("kv_get", { namespace: KV_NS, key });
  if (res === undefined) return undefined;
  if (!res.found) return null;
  return typeof res.value === "string" ? res.value : null;
}

function kvWrite(key: string, payload: string): boolean {
  const res = kvCall<{ ok?: boolean }>("kv_set", {
    namespace: KV_NS,
    key,
    value_json: JSON.stringify(payload),
  });
  return res?.ok === true;
}

function writeLocal(entries: Pair[]): void {
  for (const [, localKey, value] of entries) {
    try {
      localStorage.setItem(localKey, value);
    } catch (e) {
      console.warn("session persist failed", e);
    }
  }
}

/** [KV 键, localStorage 键, 序列化后的值] */
type Pair = [string, string, string];

/**
 * 写入一组键值。KV 可用时写 KV；任一键失败（例如单值 64KB 上限）则把整组
 * 镜像进 localStorage，保证本次会话内容仍可读回，不会因超限丢消息。
 */
function persist(entries: Pair[]): void {
  if (!hasKvBridge()) {
    writeLocal(entries);
    return;
  }
  let ok = true;
  for (const [key, , value] of entries) {
    if (!kvWrite(key, value)) ok = false;
  }
  if (ok) return;
  console.warn("session KV persist failed, mirroring to localStorage");
  writeLocal(entries);
}

function forget(key: string, localKey: string): void {
  if (hasKvBridge()) kvCall("kv_delete", { namespace: KV_NS, key });
  localStorage.removeItem(localKey);
}

/* ----------------------------- index loading ---------------------------- */

function loadLocalIndex(): SessionMeta[] {
  const list = safeParse<SessionMeta[]>(localStorage.getItem(INDEX_KEY));
  if (list && Array.isArray(list) && list.length > 0) {
    return sortIndex(list);
  }
  // One-time migration: adopt the v1 single-conversation history.
  const legacy = safeParse<PiMessage[]>(localStorage.getItem(LEGACY_KEY));
  if (legacy && Array.isArray(legacy) && legacy.length > 0) {
    const firstUser = legacy.find((m) => m.role === "user");
    const title = firstText(firstUser).slice(0, 24) || "历史对话";
    const meta: SessionMeta = { id: newId(), title, updatedAt: Date.now() };
    localStorage.setItem(SESSION_KEY(meta.id), JSON.stringify(legacy));
    localStorage.setItem(INDEX_KEY, JSON.stringify([meta]));
    return [meta];
  }
  return [];
}

/**
 * 首次在 KV 模式下读取：把 localStorage 的会话复制到 KV（复制，不移动）。
 * 单个会话超限时跳过（读取时会回退 localStorage），索引仍写入以便列表完整。
 */
function migrateToKv(): SessionMeta[] {
  const local = loadLocalIndex();
  if (local.length === 0) return [];
  let copied = 0;
  for (const meta of local) {
    const raw = localStorage.getItem(SESSION_KEY(meta.id));
    if (!raw) continue;
    if (kvWrite(kvSessionKey(meta.id), raw)) copied++;
    else console.warn(`session KV migrate skipped: ${meta.id}`);
  }
  if (copied === 0) return local;
  if (!kvWrite(KV_INDEX_KEY, JSON.stringify(local))) return local;
  return sortIndex(local);
}

function loadIndex(): SessionMeta[] {
  if (hasKvBridge()) {
    const raw = kvRead(KV_INDEX_KEY);
    if (typeof raw === "string") {
      const list = safeParse<SessionMeta[]>(raw);
      if (Array.isArray(list)) return sortIndex(list);
    }
    if (raw === null) return migrateToKv();
  }
  return loadLocalIndex();
}

/* ------------------------------ session body ---------------------------- */

function loadSession(id: string): PiMessage[] {
  if (hasKvBridge()) {
    const raw = kvRead(kvSessionKey(id));
    if (typeof raw === "string") return safeParse<PiMessage[]>(raw) ?? [];
  }
  return safeParse<PiMessage[]>(localStorage.getItem(SESSION_KEY(id))) ?? [];
}

function firstText(message: PiMessage | undefined): string {
  if (!message) return "";
  const content = (message as { content?: unknown }).content;
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .map((p) => (p && typeof p === "object" && (p as { text?: string }).text) || "")
      .filter(Boolean)
      .join(" ");
  }
  return "";
}

function newId(): string {
  return `s_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

export const SessionStore = {
  list(): SessionMeta[] {
    return loadIndex();
  },

  load(id: string): PiMessage[] {
    return loadSession(id);
  },

  save(id: string, messages: PiMessage[]): SessionMeta[] {
    const index = loadIndex();
    const firstUser = messages.find((m) => m.role === "user");
    const autoTitle = firstText(firstUser).slice(0, 24) || "新对话";
    const existing = index.find((s) => s.id === id);
    const total = sumMessageUsage(messages);
    const meta: SessionMeta = {
      id,
      title: existing?.title && existing.title !== "新对话" ? existing.title : autoTitle,
      updatedAt: Date.now(),
      usage: { input: total.input, output: total.output },
    };
    const next = [meta, ...index.filter((s) => s.id !== id)];
    persist([
      [kvSessionKey(id), SESSION_KEY(id), JSON.stringify(messages)],
      [KV_INDEX_KEY, INDEX_KEY, JSON.stringify(next)],
    ]);
    // KV 模式保留 localStorage 原始键（迁移备份），浏览器模式维持旧行为。
    if (!hasKvBridge()) localStorage.removeItem(LEGACY_KEY);
    return sortIndex(next);
  },

  rename(id: string, title: string): SessionMeta[] {
    const index = loadIndex();
    const next = index.map((s) => (s.id === id ? { ...s, title: title.trim() || s.title } : s));
    persist([[KV_INDEX_KEY, INDEX_KEY, JSON.stringify(next)]]);
    return sortIndex(next);
  },

  remove(id: string): SessionMeta[] {
    const index = loadIndex().filter((s) => s.id !== id);
    forget(kvSessionKey(id), SESSION_KEY(id));
    persist([[KV_INDEX_KEY, INDEX_KEY, JSON.stringify(index)]]);
    return index;
  },
};
