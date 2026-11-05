/**
 * Multi-session storage for the drawer: sessions are named PiMessage[]
 * transcripts in localStorage. v1 legacy key ("andagent.messages.v1") is
 * migrated into the index as the first session on first read.
 */

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

// Weakly typed on purpose: values cross the localStorage serialization boundary.
type PiMessage = any; // eslint-disable-line @typescript-eslint/no-explicit-any

function safeParse<T>(raw: string | null): T | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

function loadIndex(): SessionMeta[] {
  const list = safeParse<SessionMeta[]>(localStorage.getItem(INDEX_KEY));
  if (list && Array.isArray(list) && list.length > 0) {
    return list.sort((a, b) => b.updatedAt - a.updatedAt);
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
    return safeParse<PiMessage[]>(localStorage.getItem(SESSION_KEY(id))) ?? [];
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
    try {
      localStorage.setItem(SESSION_KEY(id), JSON.stringify(messages));
      localStorage.setItem(INDEX_KEY, JSON.stringify(next));
      localStorage.removeItem(LEGACY_KEY);
    } catch (e) {
      console.warn("session persist failed", e);
    }
    return next.sort((a, b) => b.updatedAt - a.updatedAt);
  },

  rename(id: string, title: string): SessionMeta[] {
    const index = loadIndex();
    const next = index.map((s) => (s.id === id ? { ...s, title: title.trim() || s.title } : s));
    localStorage.setItem(INDEX_KEY, JSON.stringify(next));
    return next.sort((a, b) => b.updatedAt - a.updatedAt);
  },

  remove(id: string): SessionMeta[] {
    const index = loadIndex().filter((s) => s.id !== id);
    localStorage.removeItem(SESSION_KEY(id));
    localStorage.setItem(INDEX_KEY, JSON.stringify(index));
    return index;
  },
};
