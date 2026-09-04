/** 与 recsys-common RecallChannel 枚举对齐,供召回沙盘多选。 */
export const RECALL_CHANNELS = [
  'VECTOR',
  'I2I',
  'HOT',
  'TAG',
  'U2U',
  'SWING',
  'SEMANTIC',
  'COLD',
  'TWO_TOWER',
  'MULTI_INTEREST',
  'GRAPH',
  'GENERATIVE',
  'LEXICAL',
  'TIGER',
] as const;

export type RecallChannelName = (typeof RECALL_CHANNELS)[number];

export function encodeRecallChannels(channels: string[]): string {
  return channels
    .map((c) => c.trim().toUpperCase())
    .filter(Boolean)
    .join(',');
}

export function decodeRecallChannels(csv: string | undefined): string[] {
  if (!csv?.trim()) return [];
  return csv
    .split(',')
    .map((c) => c.trim().toUpperCase())
    .filter(Boolean);
}
