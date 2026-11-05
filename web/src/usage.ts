/**
 * Token usage formatting and aggregation (R40).
 * Pi AssistantMessage.usage 携带 input/output/cacheRead/cacheWrite/totalTokens；
 * 这里只聚合计费无关的三项（不做费用换算——价格表维护超出范围）。
 */

export interface UsageSum {
  input: number;
  output: number;
  totalTokens: number;
}

export function emptyUsage(): UsageSum {
  return { input: 0, output: 0, totalTokens: 0 };
}

type UsageLike = { input?: number; output?: number; totalTokens?: number } | null | undefined;

/** In-place accumulate; missing fields count as 0 (aborted/error messages carry zeros). */
export function addUsage(acc: UsageSum, usage: UsageLike): void {
  acc.input += usage?.input ?? 0;
  acc.output += usage?.output ?? 0;
  acc.totalTokens += usage?.totalTokens ?? 0;
}

/**
 * Sum usage over a PiMessage[] transcript (assistant messages only).
 * Aborted messages are skipped: pi 的 aborted 分支塞的是 ZERO_USAGE，无消耗可回报。
 */
export function sumMessageUsage(messages: readonly unknown[]): UsageSum {
  const acc = emptyUsage();
  for (const message of messages) {
    const msg = message as {
      role?: string;
      stopReason?: string;
      usage?: UsageLike;
    };
    if (msg?.role !== "assistant" || msg.stopReason === "aborted") continue;
    addUsage(acc, msg.usage);
  }
  return acc;
}

/** <1000 原样，否则 k 缩写保留 1 位小数（842 → "842"，45600 → "45.6k"）。 */
export function fmtTokens(n: number): string {
  if (!Number.isFinite(n) || n <= 0) return "0";
  if (n < 1000) return String(Math.round(n));
  return `${(n / 1000).toFixed(1)}k`;
}
