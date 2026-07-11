import { PALETTE } from './echartsTheme';
import { rgba } from '../../theme/tokens';
import ChartFrame from './ChartFrame';

export interface RadarIndicator {
  name: string;
  max: number;
}
export interface RadarSeriesItem {
  name: string;
  values: number[];
}

// 通用雷达图:多维对比(每个 series 一圈)。用于 eval 多策略×多指标、ad-quality 相关性/CTR/CVR 三因子。
// 各维量纲不同,故 indicator 必须带各自的 max(由调用方按列域计算),否则大量纲维度会压扁小量纲。
export default function ERadar({
  indicators,
  series,
  height = 360,
  fileName = 'radar.png',
}: {
  indicators: RadarIndicator[];
  series: RadarSeriesItem[];
  height?: number;
  fileName?: string;
}) {
  const option = {
    tooltip: { trigger: 'item' as const },
    legend: { top: 0, type: 'scroll' as const },
    radar: {
      indicator: indicators.map((d) => ({ name: d.name, max: d.max > 0 ? d.max : 1 })),
      radius: '62%',
      center: ['50%', '56%'],
      splitNumber: 4,
      axisName: { color: '#6b7280', fontSize: 12 },
      axisLine: { lineStyle: { color: '#d9dde5' } },
      splitLine: { lineStyle: { color: '#eef1f6' } },
      splitArea: { areaStyle: { color: ['transparent', rgba('#2d6cdf', 0.03)] } },
    },
    series: [
      {
        type: 'radar' as const,
        emphasis: { focus: 'series' as const, lineStyle: { width: 3 } },
        data: series.map((s, i) => {
          const c = PALETTE[i % PALETTE.length];
          return {
            name: s.name,
            value: s.values,
            symbolSize: 4,
            lineStyle: { color: c, width: 2 },
            itemStyle: { color: c },
            areaStyle: { color: rgba(c, 0.1) },
          };
        }),
      },
    ],
  };
  return <ChartFrame option={option} height={height} fileName={fileName} />;
}
