// 全站视觉设计 token 的单一事实源。
// 之前品牌色 #2d6cdf、漏斗深色 #080d1a 等散落在多个组件里硬编码;此处收拢,别处一律 import。

export const BRAND = '#2d6cdf'; // 主品牌蓝(= AntD colorPrimary)
export const FUNNEL_BG = '#080d1a'; // 漏斗带/深色科技面的底色

// 漏斗各阶段的强调色阶 —— 被漏斗 stage、ScoreBar、图表复用,保证同一语义同一色。
export const ACCENTS = {
  recall: '#2d6cdf', // 召回
  rank: '#22d3ee', // 排序
  rerank: '#8b5cf6', // 重排
  ad: '#f59e0b', // 广告/竞价
  gsp: '#2ee6a6', // 计费/成交
  warn: '#ff6b72', // 异常
} as const;

// 链路健康态色(在线/检测中/离线),供漏斗状态点与 hero 复用。
export const STATUS = {
  online: '#2ee6a6',
  checking: '#ffcf5c',
  offline: '#ff6b72',
} as const;

// 全站表面色:有方向感的多层光晕页底(左上蓝 / 右上青 / 右下紫,与漏斗带同色语言)+ 侧栏微渐变 + 卡片描边/阴影。
export const SURFACE = {
  page:
    'radial-gradient(1100px 560px at 4% -10%, rgba(45,108,223,0.16), transparent 60%),' +
    'radial-gradient(820px 480px at 98% -8%, rgba(34,211,238,0.12), transparent 56%),' +
    'radial-gradient(960px 620px at 104% 112%, rgba(139,92,246,0.14), transparent 58%),' +
    'linear-gradient(160deg, #e6edff 0%, #eef2fb 46%, #f0e9ff 100%)',
  sider: 'linear-gradient(180deg, #ffffff 0%, #f6f8ff 100%)', // 侧栏:淡竖向渐变
  cardBorder: '#eef1f7', // 卡片/表格描边(柔)
  cardShadow: '0 1px 2px rgba(20,40,90,0.04), 0 10px 28px -14px rgba(20,40,90,0.14)',
} as const;

// #rrggbb → rgba():把强调色派生成柔光 / 描边 / 流点色(纯函数,零依赖)。
// 原实现在 RecFunnelHero.tsx 内联,移到此处全站共用。
export function rgba(hex: string, a: number): string {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
}

// AntD 预设色名(channelColors.ts 用它)→ 主色 hex,供 ECharts / 边框等需要真实 hex 的场景。
// 取 @ant-design/colors 各色板的主色(-6 档),与 <Tag color="..."> 的观感对齐。
const PRESET_HEX: Record<string, string> = {
  red: '#f5222d',
  volcano: '#fa541c',
  orange: '#fa8c16',
  gold: '#faad14',
  yellow: '#fadb14',
  lime: '#a0d911',
  green: '#52c41a',
  cyan: '#13c2c2',
  blue: '#1677ff',
  geekblue: '#2f54eb',
  purple: '#722ed1',
  magenta: '#eb2f96',
  pink: '#eb2f96',
};

export function hexOfPreset(name: string): string {
  return PRESET_HEX[name] ?? BRAND;
}
