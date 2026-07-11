import { ACCENTS } from '../../theme/tokens';
import ChartFrame from './ChartFrame';

// axisLine.color 的分段:[结束分位(0..1), 颜色][]。最后一段的分位必须为 1(覆盖满量程)。
export type GaugeBand = [number, string];

// 语义化配色助手 —— 越低越好(如 ECE/PSI):绿→金→红。warnAt/badAt 为「值」,内部换算成分位。
export function lowerBetterBands(min: number, max: number, warnAt: number, badAt: number): GaugeBand[] {
  const f = (v: number) => Math.min(1, Math.max(0, (v - min) / (max - min || 1)));
  return [
    [f(warnAt), ACCENTS.gsp],
    [f(badAt), ACCENTS.ad],
    [1, ACCENTS.warn],
  ];
}
// 越高越好(如覆盖率):红→金→绿。
export function higherBetterBands(min: number, max: number, warnAt: number, goodAt: number): GaugeBand[] {
  const f = (v: number) => Math.min(1, Math.max(0, (v - min) / (max - min || 1)));
  return [
    [f(warnAt), ACCENTS.warn],
    [f(goodAt), ACCENTS.ad],
    [1, ACCENTS.gsp],
  ];
}

// 通用单值仪表盘:健康度直观量表。用于 ECE / PSI / 覆盖率 / 预算消耗 / pacing / 质量度乘子等单值。
// bands 给定分段配色(默认单段中性色);当前值落在哪一段就用该段色描指针/进度/读数,一眼看出健康态。
export default function EGauge({
  value,
  min = 0,
  max,
  title,
  bands,
  format,
  height = 220,
  fileName = 'gauge.png',
}: {
  value: number;
  min?: number;
  max: number;
  title: string;
  bands?: GaugeBand[];
  format?: (v: number) => string;
  height?: number;
  fileName?: string;
}) {
  const safeVal = Number.isFinite(value) ? value : 0;
  const segs: GaugeBand[] = bands && bands.length ? bands : [[1, ACCENTS.rank]];
  const frac = max === min ? 0 : (safeVal - min) / (max - min);
  const active = segs.find(([stop]) => frac <= stop) ?? segs[segs.length - 1];
  const color = active[1];
  const fmt = format ?? ((v: number) => (Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(3)));

  const option = {
    series: [
      {
        type: 'gauge' as const,
        min,
        max,
        startAngle: 210,
        endAngle: -30,
        radius: '96%',
        center: ['50%', '60%'],
        progress: { show: true, width: 10, roundCap: true, itemStyle: { color } },
        axisLine: { lineStyle: { width: 10, color: segs } },
        pointer: { length: '58%', width: 5, itemStyle: { color } },
        anchor: { show: true, size: 12, showAbove: true, itemStyle: { color } },
        axisTick: { distance: -12, length: 4, lineStyle: { color: '#c9ced8' } },
        splitLine: { distance: -12, length: 10, lineStyle: { color: '#c9ced8' } },
        axisLabel: {
          distance: 12,
          color: '#9aa3b2',
          fontSize: 9,
          formatter: (v: number) => (Math.abs(v) >= 1000 ? (v / 1000).toFixed(0) + 'k' : `${+v.toFixed(2)}`),
        },
        title: { offsetCenter: [0, '80%'], color: '#6b7280', fontSize: 12 },
        detail: {
          valueAnimation: true,
          offsetCenter: [0, '32%'],
          formatter: (v: number) => fmt(v),
          color,
          fontSize: 22,
          fontWeight: 700 as const,
        },
        data: [{ value: safeVal, name: title }],
      },
    ],
  };
  return <ChartFrame option={option} height={height} fileName={fileName} />;
}
