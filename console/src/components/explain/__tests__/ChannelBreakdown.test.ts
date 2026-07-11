import { describe, it, expect } from 'vitest';
import { deriveChannelStats } from '../ChannelBreakdown';
import type { RecommendExplain, RecommendItem } from '../../../api/types';

// deriveChannelStats 把 explain 的逐路计数(channelRecall/channelContribution)+ final items 的
// recallFrom 归约成逐通道三级统计(rawCount → contribution → survived)。纯函数,这里只断言归约口径。

const item = (itemId: number, recallFrom: string[]): RecommendItem => ({ itemId, score: 1, recallFrom, reason: '' });

describe('deriveChannelStats', () => {
  it('returns [] for empty explain and no items', () => {
    expect(deriveChannelStats(null, [])).toEqual([]);
    expect(deriveChannelStats(undefined, [])).toEqual([]);
  });

  it('joins rawCount / contribution / survived per channel, sorted by rawCount desc', () => {
    const explain = {
      stages: [],
      channelRecall: [
        { channel: 'VECTOR', rawCount: 100 },
        { channel: 'HOT', rawCount: 50 },
        { channel: 'I2I', rawCount: 20 },
      ],
      channelContribution: [
        { channel: 'VECTOR', count: 30 },
        { channel: 'HOT', count: 40 },
        { channel: 'I2I', count: 10 },
      ],
      scores: {},
    } as unknown as RecommendExplain;
    const items = [item(1, ['VECTOR', 'HOT']), item(2, ['HOT']), item(3, ['VECTOR'])];

    const stats = deriveChannelStats(explain, items);
    expect(stats.map((s) => s.channel)).toEqual(['VECTOR', 'HOT', 'I2I']); // rawCount desc
    expect(stats[0]).toEqual({ channel: 'VECTOR', rawCount: 100, contribution: 30, survived: 2 });
    expect(stats[1]).toEqual({ channel: 'HOT', rawCount: 50, contribution: 40, survived: 2 });
    // I2I 召回了但一条都没存活 —— 仍出现在表里,survived=0(不虚构、不丢通道)。
    expect(stats[2]).toEqual({ channel: 'I2I', rawCount: 20, contribution: 10, survived: 0 });
  });

  it('includes channels present only in items.recallFrom (rawCount 0) via union', () => {
    const explain = {
      stages: [],
      channelRecall: [{ channel: 'VECTOR', rawCount: 10 }],
      channelContribution: [{ channel: 'VECTOR', count: 5 }],
      scores: {},
    } as unknown as RecommendExplain;
    const stats = deriveChannelStats(explain, [item(1, ['VECTOR', 'COLD'])]);
    const cold = stats.find((s) => s.channel === 'COLD');
    expect(cold).toEqual({ channel: 'COLD', rawCount: 0, contribution: 0, survived: 1 });
  });

  it('counts survival at most once per item even with duplicate recallFrom entries', () => {
    const stats = deriveChannelStats(null, [item(1, ['HOT', 'HOT'])]);
    expect(stats).toEqual([{ channel: 'HOT', rawCount: 0, contribution: 0, survived: 1 }]);
  });
});
