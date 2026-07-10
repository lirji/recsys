import ReactECharts from 'echarts-for-react';
import { ECHARTS_THEME, PALETTE, barGradient } from './echartsTheme';

export interface BarSeries {
  name: string;
  data: number[];
}

// 通用分组柱状图封装(ECharts)。广告报表 / 离线报表复用。接入 recsys 主题:渐变柱 + 深玻璃 tooltip。
export default function EBar({
  categories,
  series,
  height = 320,
  yName,
}: {
  categories: string[];
  series: BarSeries[];
  height?: number;
  yName?: string;
}) {
  const option = {
    tooltip: { trigger: 'axis' as const },
    legend: { top: 0 },
    grid: { left: 48, right: 20, top: 36, bottom: 40 },
    xAxis: { type: 'category' as const, data: categories, axisLabel: { interval: 0, rotate: categories.length > 8 ? 30 : 0 } },
    yAxis: { type: 'value' as const, name: yName },
    series: series.map((s, i) => ({
      name: s.name,
      type: 'bar' as const,
      data: s.data,
      itemStyle: { color: barGradient(PALETTE[i % PALETTE.length]), borderRadius: [4, 4, 0, 0] as [number, number, number, number] },
    })),
  };
  return <ReactECharts option={option} theme={ECHARTS_THEME} style={{ height }} notMerge lazyUpdate />;
}
