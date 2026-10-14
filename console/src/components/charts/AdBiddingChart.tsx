import ReactECharts from 'echarts-for-react';
import type { SponsoredAd } from '../../api/types';
import { ACCENTS } from '../../theme/tokens';
import { ECHARTS_THEME, barGradient } from './echartsTheme';
import { adPriceUnit } from '../../utils/adPricing';

// 排序分和各计费单位的报价不可直接比较；图中只展示逐广告报价，明确其单位。

const r4 = (n: number) => (Number.isFinite(n) ? +n.toFixed(4) : null);

export default function AdBiddingChart({ ads }: { ads: SponsoredAd[] }) {
  const cats = ads.map((a) => `位${a.position}\n${adPriceUnit(a.bidType)}`);

  const option = {
    grid: { left: 52, right: 52, top: 40, bottom: 40 },
    legend: { top: 4, data: ['计价单价', 'pCTR(校准)'] },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      // params: 该 x 位置上各系列的数据点;用 dataIndex 回查广告补充标题/通道/计费类型。
      formatter: (params: any[]) => {
        if (!params?.length) return '';
        const a = ads[params[0].dataIndex];
        const head = `<b>位${a.position} · ${a.title || '#' + a.itemId}</b><br/>通道 ${a.channel} · 计费 ${a.bidType}`;
        const rows = params.map((p) => `${p.marker}${p.seriesName}: <b>${p.value ?? '—'}</b>${p.seriesName === '计价单价' ? ` ${adPriceUnit(a.bidType)}` : ''}`).join('<br/>');
        return `${head}<br/>${rows}`;
      },
    },
    xAxis: {
      type: 'category',
      data: cats,
      axisLabel: { interval: 0, rotate: cats.length > 8 ? 30 : 0 },
    },
    yAxis: [
      { type: 'value', name: '报价（各自单位）', splitLine: { show: true } },
      { type: 'value', name: 'pCTR', position: 'right', splitLine: { show: false }, min: 0 },
    ],
    series: [
      {
        name: '计价单价',
        type: 'bar',
        data: ads.map((a) => r4(a.chargedPrice)),
        emphasis: { focus: 'series' },
        itemStyle: { color: barGradient(ACCENTS.gsp), borderRadius: [4, 4, 0, 0] },
      },
      {
        name: 'pCTR(校准)',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbolSize: 7,
        data: ads.map((a) => r4(a.pctrCalibrated)),
        emphasis: { focus: 'series' },
        lineStyle: { width: 2, color: ACCENTS.rerank },
        itemStyle: { color: ACCENTS.rerank },
      },
    ],
  };

  return <ReactECharts option={option} theme={ECHARTS_THEME} style={{ height: 340 }} notMerge lazyUpdate />;
}
