import { PALETTE, barGradient } from './echartsTheme';
import { rgba } from '../../theme/tokens';
import ChartFrame from './ChartFrame';

const WHISKER = '#3b4657'; // 误差须的深灰,压过渐变柱以保证可读

// 带置信区间误差棒的柱状图:柱=点估计,上下须=CI(如 AB 报表逐桶 CTR 的 Wilson 95% 区间)。
// 误差须用 custom series 的 renderItem 手绘(竖线 + 上下横帽),与柱共用同一类目轴对齐。
// markers[i] 非空则在柱顶标注(如「★显著」),一眼分出与基线有显著差异的桶。
//
// 可选(向后兼容,全部 undefined 时行为与原先逐字节一致):
//   baselineValue —— 画一条横向「基线」虚线锚点,让每根柱可读成「相对基线」。
//   highlightIndex + highlightColor —— 把某一根柱(如基线柱)换成实心高亮 + 虚线描边。
export default function EErrorBar({
  categories,
  values,
  low,
  high,
  barName = '值',
  yName,
  markers,
  height = 340,
  colorIndex = 1,
  fileName = 'errorbar.png',
  baselineValue,
  highlightIndex,
  highlightColor,
}: {
  categories: string[];
  values: number[];
  low: number[];
  high: number[];
  barName?: string;
  yName?: string;
  markers?: (string | null)[];
  height?: number;
  colorIndex?: number;
  fileName?: string;
  baselineValue?: number;
  highlightIndex?: number;
  highlightColor?: string;
}) {
  const base = PALETTE[colorIndex % PALETTE.length];
  const fmt = (n: number) => (Number.isFinite(n) ? +n.toFixed(4) : 0);

  const option = {
    grid: { left: 52, right: 20, top: 40, bottom: 48 },
    legend: { top: 0, data: [barName, 'CI'] },
    tooltip: {
      trigger: 'axis' as const,
      axisPointer: { type: 'shadow' as const },
      formatter: (params: { dataIndex: number }[]) => {
        if (!params?.length) return '';
        const i = params[0].dataIndex;
        const mk = markers?.[i] ? `<br/><b>${markers[i]}</b>` : '';
        return `<b>${categories[i]}</b><br/>${barName}: <b>${fmt(values[i])}</b><br/>95% CI: [${fmt(low[i])}, ${fmt(high[i])}]${mk}`;
      },
    },
    xAxis: {
      type: 'category' as const,
      data: categories,
      axisLabel: { interval: 0, rotate: categories.length > 6 ? 30 : 0, width: 120, overflow: 'truncate' as const },
    },
    yAxis: { type: 'value' as const, name: yName, min: 0 },
    series: [
      {
        name: barName,
        type: 'bar' as const,
        // highlightIndex 未传 → 与原先逐字节一致(纯 number 数组);传入则逐项 itemStyle 只高亮该柱。
        data:
          highlightIndex == null
            ? values.map(fmt)
            : values.map((v, i) => ({
                value: fmt(v),
                itemStyle:
                  i === highlightIndex
                    ? {
                        color: rgba(highlightColor ?? '#22d3ee', 0.85),
                        borderColor: highlightColor ?? '#22d3ee',
                        borderWidth: 1,
                        borderType: 'dashed' as const,
                        borderRadius: [4, 4, 0, 0] as [number, number, number, number],
                      }
                    : { color: barGradient(base), borderRadius: [4, 4, 0, 0] as [number, number, number, number] },
              })),
        barMaxWidth: 40,
        itemStyle: { color: barGradient(base), borderRadius: [4, 4, 0, 0] as [number, number, number, number] },
        // baselineValue 未传 → markLine=undefined,ECharts 视作不存在,与原先渲染一致。
        markLine:
          baselineValue != null
            ? {
                silent: true,
                symbol: 'none' as const,
                lineStyle: { color: '#8a94a6', type: 'dashed' as const, width: 1 },
                label: { formatter: '基线', color: '#8a94a6', fontSize: 11, position: 'insideEndTop' as const },
                data: [{ yAxis: baselineValue }],
              }
            : undefined,
        label: markers
          ? {
              show: true,
              position: 'top' as const,
              fontSize: 11,
              color: WHISKER,
              formatter: (p: { dataIndex: number }) => markers[p.dataIndex] ?? '',
            }
          : undefined,
      },
      {
        name: 'CI',
        type: 'custom' as const,
        // data: [类目索引, low, high];renderItem 用类目轴坐标把 low/high 画成误差须。
        data: categories.map((_, i) => [i, fmt(low[i]), fmt(high[i])]),
        encode: { x: 0, y: [1, 2] },
        z: 5,
        tooltip: { show: false },
        renderItem: (_params: unknown, api: { value: (i: number) => number; coord: (p: number[]) => number[]; size?: (v: number[]) => number[] }) => {
          const xi = api.value(0);
          const lowP = api.coord([xi, api.value(1)]);
          const highP = api.coord([xi, api.value(2)]);
          const cap = Math.max(3, (api.size ? api.size([1, 0])[0] : 20) * 0.14);
          const style = { stroke: WHISKER, lineWidth: 1.5, fill: rgba('#ffffff', 0) };
          const seg = (x1: number, y1: number, x2: number, y2: number) => ({ type: 'line' as const, shape: { x1, y1, x2, y2 }, style });
          return {
            type: 'group' as const,
            children: [
              seg(highP[0] - cap, highP[1], highP[0] + cap, highP[1]),
              seg(highP[0], highP[1], lowP[0], lowP[1]),
              seg(lowP[0] - cap, lowP[1], lowP[0] + cap, lowP[1]),
            ],
          };
        },
      },
    ],
  };
  return <ChartFrame option={option} height={height} fileName={fileName} />;
}
