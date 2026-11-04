import { useEffect, useRef, useState } from "react";
import * as echarts from "echarts/core";
import { BarChart, LineChart, PieChart } from "echarts/charts";
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsCoreOption } from "echarts/core";

echarts.use([
  BarChart,
  LineChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  CanvasRenderer,
]);

/** Dark theme defaults so model-provided options stay readable in the app. */
const DARK_THEME = {
  backgroundColor: "transparent",
  color: ["#7f9bff", "#4fd1a1", "#ffb95f", "#ff7a7a", "#a07bff", "#4fc3f7"],
  textStyle: { color: "#c9d1e3" },
  title: { textStyle: { color: "#eef1f8" }, subtextStyle: { color: "#8b93a3" } },
  legend: { textStyle: { color: "#aab3c6" } },
  categoryAxis: {
    axisLine: { lineStyle: { color: "rgba(255,255,255,0.18)" } },
    axisLabel: { color: "#8b93a3" },
    splitLine: { lineStyle: { color: "rgba(255,255,255,0.05)" } },
  },
  valueAxis: {
    axisLine: { lineStyle: { color: "rgba(255,255,255,0.18)" } },
    axisLabel: { color: "#8b93a3" },
    splitLine: { lineStyle: { color: "rgba(255,255,255,0.06)" } },
  },
};

const ALLOWED_TYPES = new Set(["bar", "line", "pie"]);

function parseOption(code: string): EChartsCoreOption {
  let parsed: unknown;
  try {
    parsed = JSON.parse(code);
  } catch (e) {
    throw new Error(`option JSON 解析失败：${e instanceof Error ? e.message : String(e)}`);
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("option 必须是一个 JSON 对象");
  }
  const option = parsed as Record<string, unknown>;
  const rawSeries = option.series;
  const series = Array.isArray(rawSeries) ? rawSeries : rawSeries ? [rawSeries] : [];
  if (series.length === 0) throw new Error("option 缺少 series");
  for (const entry of series) {
    const type = (entry as { type?: unknown } | null)?.type;
    if (typeof type !== "string" || !ALLOWED_TYPES.has(type)) {
      throw new Error(`不支持的图表类型：${String(type ?? "未指定")}（仅支持 bar/line/pie）`);
    }
  }
  return option as EChartsCoreOption;
}

/**
 * Renders a ```chart code fence. The body is pure ECharts option JSON — no JS
 * expressions — parsed once per code change; the instance is disposed with the
 * component and resized with its container.
 */
export function ChartCard({ code }: { code: string }) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    let option: EChartsCoreOption;
    try {
      option = parseOption(code);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return;
    }
    setError(null);

    let chart: echarts.ECharts | null = null;
    try {
      chart = echarts.init(host, DARK_THEME, { renderer: "canvas" });
      chart.setOption(option, true);
    } catch (e) {
      chart?.dispose();
      setError(`图表渲染失败：${e instanceof Error ? e.message : String(e)}`);
      return;
    }

    const observer = new ResizeObserver(() => chart?.resize());
    observer.observe(host);
    return () => {
      observer.disconnect();
      chart?.dispose();
    };
  }, [code]);

  if (error) {
    return (
      <div className="chart-card chart-error">
        <div className="chart-head">
          <span className="chart-badge">图表</span>
          <span className="chart-error-text">{error}</span>
        </div>
      </div>
    );
  }

  return (
    <div className="chart-card">
      <div className="chart-head">
        <span className="chart-badge">图表</span>
      </div>
      <div ref={hostRef} className="chart-body" />
    </div>
  );
}
