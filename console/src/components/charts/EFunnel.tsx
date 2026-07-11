import { PALETTE, barGradient } from './echartsTheme';
import ChartFrame from './ChartFrame';

export interface FunnelStage {
  name: string;
  value: number;
}

// 通用漏斗图:曝光 → 点击 → 转化。tooltip 同时给出整体转化率(相对首阶)与逐级转化率(相对上一阶)。
export default function EFunnel({
  stages,
  height = 340,
  fileName = 'funnel.png',
}: {
  stages: FunnelStage[];
  height?: number;
  fileName?: string;
}) {
  const first = stages[0]?.value ?? 0;
  const pct = (n: number, d: number) => (d > 0 ? ((n / d) * 100).toFixed(2) + '%' : '—');

  const option = {
    tooltip: {
      trigger: 'item' as const,
      // dataIndex 回查上一阶,给出整体 + 逐级转化率。
      formatter: (p: { dataIndex: number; marker: string; name: string; value: number }) => {
        const i = p.dataIndex;
        const prev = i > 0 ? stages[i - 1].value : p.value;
        const overall = `整体(相对 ${stages[0]?.name}): <b>${pct(p.value, first)}</b>`;
        const step = i > 0 ? `<br/>逐级(相对 ${stages[i - 1].name}): <b>${pct(p.value, prev)}</b>` : '';
        return `${p.marker}<b>${p.name}</b>: ${p.value}<br/>${overall}${step}`;
      },
    },
    legend: { top: 0, data: stages.map((s) => s.name) },
    series: [
      {
        type: 'funnel' as const,
        left: '8%',
        right: '8%',
        top: 40,
        bottom: 12,
        minSize: '24%',
        maxSize: '100%',
        sort: 'descending' as const,
        gap: 2,
        funnelAlign: 'center' as const,
        label: {
          show: true,
          position: 'inside' as const,
          color: '#fff',
          fontWeight: 600 as const,
          formatter: (p: { name: string; value: number }) => `${p.name}\n${p.value}`,
        },
        labelLine: { show: false },
        itemStyle: { borderColor: '#fff', borderWidth: 1 },
        emphasis: { label: { fontSize: 15 } },
        data: stages.map((s, i) => ({
          name: s.name,
          value: s.value,
          itemStyle: { color: barGradient(PALETTE[i % PALETTE.length]) },
        })),
      },
    ],
  };
  return <ChartFrame option={option} height={height} fileName={fileName} />;
}
