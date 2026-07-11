import * as echarts from 'echarts';
import { hexOfPreset, rgba } from '../../theme/tokens';

// 全站统一的 ECharts 主题:浅色轴/网格 + 深玻璃 tooltip + 品牌调色板(对齐 channelColors 的预设色顺序)。
// 只在本模块 registerTheme,与 echarts-for-react 共享同一全局实例,故图表侧 theme="recsys" 可解析。
// 本模块只被懒加载的图表引用(EBar / AdBiddingChart),绝不被 main.tsx / FunnelBand 引用 → echarts 不进首屏包。

export const ECHARTS_THEME = 'recsys';

// 与 channelColors.ts 的 PRESET 顺序一致,让「按系列/类目上色」跨图表、跨页面观感统一。
export const PALETTE = [
  'blue',
  'green',
  'cyan',
  'purple',
  'magenta',
  'geekblue',
  'volcano',
  'gold',
  'lime',
  'orange',
  'red',
].map(hexOfPreset);

const AXIS_LABEL = { color: '#6b7280' };
const AXIS_LINE = { lineStyle: { color: '#d9dde5' } };

const RECSYS_THEME = {
  color: PALETTE,
  backgroundColor: 'transparent',
  textStyle: { fontFamily: "-apple-system, 'PingFang SC', Roboto, sans-serif", color: '#4b5563' },
  title: { textStyle: { color: '#1f2937' } },
  categoryAxis: {
    axisLine: AXIS_LINE,
    axisTick: { show: false },
    axisLabel: AXIS_LABEL,
    splitLine: { show: false },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: AXIS_LABEL,
    splitLine: { lineStyle: { color: '#eef1f6' } },
    nameTextStyle: { color: '#9aa3b2', padding: [0, 0, 0, 4] },
  },
  legend: { textStyle: { color: '#4b5563' }, icon: 'roundRect', itemGap: 16, itemWidth: 14, itemHeight: 9 },
  // 柱子默认最大宽度,避免类目少时柱子过肥;显式渐变 itemStyle 的组件不受影响(只兜底 barMaxWidth)。
  bar: { barMaxWidth: 30 },
  tooltip: {
    backgroundColor: rgba('#080d1a', 0.92),
    borderColor: rgba('#82a0dc', 0.24),
    borderWidth: 1,
    textStyle: { color: '#e6edf7' },
    extraCssText: 'backdrop-filter:blur(6px);border-radius:10px;box-shadow:0 8px 28px -6px rgba(8,13,26,0.55);',
    axisPointer: {
      lineStyle: { color: rgba('#2d6cdf', 0.35) },
      crossStyle: { color: rgba('#2d6cdf', 0.35) },
      shadowStyle: { color: rgba('#2d6cdf', 0.06) },
    },
  },
  // 平滑入场,更有生命力(reduced-motion 由浏览器/组件层无法感知,故动画时长克制)。
  animationDuration: 600,
  animationEasing: 'cubicOut',
};

// 幂等注册(StrictMode 下模块可能被求值多次,registerTheme 覆盖同名亦无害,加个守卫更省事)。
let registered = false;
if (!registered) {
  echarts.registerTheme(ECHARTS_THEME, RECSYS_THEME);
  registered = true;
}

// 柱状渐变填充:顶部浓、底部淡,给柱子体积感。
export function barGradient(hex: string) {
  return {
    type: 'linear' as const,
    x: 0,
    y: 0,
    x2: 0,
    y2: 1,
    colorStops: [
      { offset: 0, color: rgba(hex, 0.95) },
      { offset: 1, color: rgba(hex, 0.45) },
    ],
  };
}
