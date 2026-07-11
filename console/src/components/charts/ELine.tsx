import { PALETTE } from './echartsTheme';
import { rgba } from '../../theme/tokens';
import ChartFrame from './ChartFrame';

export interface LineSeries {
  name: string;
  data: (number | null)[];
}

// 通用多序列折线图:跨时间戳的趋势对比(x=各次报表时间戳,一条线=一个变体/桶/位次)。
// null 值断线(缺该指标不强行连 0),便于观察某指标随离线作业迭代的走向。
export default function ELine({
  categories,
  series,
  yName,
  height = 340,
  smooth = true,
  area = false,
  fileName = 'trend.png',
}: {
  categories: string[];
  series: LineSeries[];
  yName?: string;
  height?: number;
  smooth?: boolean;
  area?: boolean;
  fileName?: string;
}) {
  const option = {
    tooltip: { trigger: 'axis' as const },
    legend: { top: 0, type: 'scroll' as const },
    grid: { left: 52, right: 20, top: 36, bottom: 48 },
    xAxis: {
      type: 'category' as const,
      boundaryGap: false,
      data: categories,
      axisLabel: { interval: 0, rotate: categories.length > 5 ? 30 : 0 },
    },
    yAxis: { type: 'value' as const, name: yName, scale: true },
    series: series.map((s, i) => {
      const c = PALETTE[i % PALETTE.length];
      return {
        name: s.name,
        type: 'line' as const,
        data: s.data,
        smooth,
        connectNulls: false,
        symbolSize: 6,
        emphasis: { focus: 'series' as const },
        lineStyle: { width: 2, color: c },
        itemStyle: { color: c },
        areaStyle: area ? { color: rgba(c, 0.08) } : undefined,
      };
    }),
  };
  return <ChartFrame option={option} height={height} fileName={fileName} />;
}
