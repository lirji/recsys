import ReactECharts from 'echarts-for-react';
import type { SponsoredAd } from '../../api/types';
import { ACCENTS } from '../../theme/tokens';
import { ECHARTS_THEME, barGradient } from './echartsTheme';

// 搜索广告「竞价链路」图:逐广告并列 bid / eCPM / 实收(GSP) 三根柱 + pCTR(校准) 折线(次 y 轴)。
// 核心故事 = GSP 次价差:实收(绿柱)通常明显矮于 eCPM(青柱),一眼看出广告主省下的价差。
// 这是 SponsoredAd 里最丰富的数据,funnel/sankey 表达不了「价值变换 + 次价差」,故用分组柱+折线。

const r4 = (n: number) => (Number.isFinite(n) ? +n.toFixed(4) : 0);

export default function AdBiddingChart({ ads }: { ads: SponsoredAd[] }) {
  const cats = ads.map((a) => `位${a.position}`);

  const option = {
    grid: { left: 52, right: 52, top: 40, bottom: 40 },
    legend: { top: 4, data: ['bid', 'eCPM', '实收(GSP)', 'pCTR(校准)'] },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      // params: 该 x 位置上各系列的数据点;用 dataIndex 回查广告补充标题/通道/计费类型。
      formatter: (params: any[]) => {
        if (!params?.length) return '';
        const a = ads[params[0].dataIndex];
        const head = `<b>位${a.position} · ${a.title || '#' + a.itemId}</b><br/>通道 ${a.channel} · 计费 ${a.bidType}`;
        const rows = params.map((p) => `${p.marker}${p.seriesName}: <b>${p.value}</b>`).join('<br/>');
        return `${head}<br/>${rows}`;
      },
    },
    xAxis: {
      type: 'category',
      data: cats,
      axisLabel: { interval: 0, rotate: cats.length > 8 ? 30 : 0 },
    },
    yAxis: [
      { type: 'value', name: '价格', splitLine: { show: true } },
      { type: 'value', name: 'pCTR', position: 'right', splitLine: { show: false }, min: 0 },
    ],
    series: [
      {
        name: 'bid',
        type: 'bar',
        data: ads.map((a) => r4(a.bid)),
        emphasis: { focus: 'series' },
        itemStyle: { color: barGradient(ACCENTS.ad), borderRadius: [4, 4, 0, 0] },
      },
      {
        name: 'eCPM',
        type: 'bar',
        data: ads.map((a) => r4(a.ecpm)),
        emphasis: { focus: 'series' },
        itemStyle: { color: barGradient(ACCENTS.rank), borderRadius: [4, 4, 0, 0] },
      },
      {
        name: '实收(GSP)',
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
