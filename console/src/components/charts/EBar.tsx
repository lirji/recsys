import ReactECharts from 'echarts-for-react';

export interface BarSeries {
  name: string;
  data: number[];
}

// 通用分组柱状图封装(ECharts)。广告报表 / 离线报表复用。
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
    series: series.map((s) => ({ name: s.name, type: 'bar' as const, data: s.data })),
  };
  return <ReactECharts option={option} style={{ height }} notMerge lazyUpdate />;
}
