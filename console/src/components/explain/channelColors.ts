// 召回通道 → 颜色的统一映射,让同一通道在所有页面用同色标签(可解释性一致)。
const PRESET = [
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
];

const EXPLICIT: Record<string, string> = {
  // 推荐召回通道
  vector: 'blue',
  semantic: 'geekblue',
  lexical: 'gold',
  tag: 'green',
  hot: 'volcano',
  i2i: 'cyan',
  u2u: 'purple',
  swing: 'magenta',
  cold: 'orange',
  two_tower: 'blue',
  generative: 'purple',
  tiger: 'magenta',
  // 广告召回通道(AdChannel)
  KW_EXACT: 'red',
  KW_BROAD: 'volcano',
  SEMANTIC_AD: 'geekblue',
  U2A: 'purple',
  HOT_AD: 'gold',
};

export function channelColor(ch: string): string {
  if (EXPLICIT[ch]) return EXPLICIT[ch];
  let h = 0;
  for (let i = 0; i < ch.length; i++) h = (h * 31 + ch.charCodeAt(i)) >>> 0;
  return PRESET[h % PRESET.length];
}
