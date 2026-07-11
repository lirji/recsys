import { describe, it, expect } from 'vitest';
import {
  deriveRecStages,
  deriveFeedStages,
  deriveQueryStages,
  deriveAdStages,
} from '../derive';
import type { FeedEntry, RecommendItem, SponsoredAd, StructuredQuery } from '../../../api/types';

// derive.* 是把在线响应派生成漏斗 stage 数组的纯函数。这里只断言派生出的
// count / metric 口径(去重通道数、条数、百分比…),不渲染、不关心 JSX icon。

describe('deriveRecStages', () => {
  it('returns null counts for an empty result (funnel at rest)', () => {
    const stages = deriveRecStages([]);
    expect(stages.map((s) => s.count)).toEqual([null, null, null]);
  });

  it('counts distinct recall channels and item totals', () => {
    const items: RecommendItem[] = [
      { itemId: 1, score: 0.9, recallFrom: ['VECTOR', 'HOT'], reason: 'r' },
      { itemId: 2, score: 0.5, recallFrom: ['HOT'], reason: 'r' },
    ];
    const [recall, rank, rerank] = deriveRecStages(items);
    expect(recall.count).toBe(2); // distinct channels: VECTOR + HOT
    expect(recall.metric?.value).toBe(2); // item count
    expect(rank.count).toBe(2);
    expect(rank.metric?.value).toBe('0.900'); // max score, fixed(3)
    expect(rerank.count).toBe(2);
  });
});

describe('deriveFeedStages', () => {
  it('derives ad load as the ad share of the blended feed', () => {
    const entries: FeedEntry[] = [
      { ad: false, itemId: 1, adId: 0, position: 0, score: 1, reason: '', recallFrom: [] },
      { ad: true, itemId: 2, adId: 9, position: 1, score: 1, reason: '', recallFrom: [] },
      { ad: false, itemId: 3, adId: 0, position: 2, score: 1, reason: '', recallFrom: [] },
      { ad: false, itemId: 4, adId: 0, position: 3, score: 1, reason: '', recallFrom: [] },
    ];
    const [natural, auction, mix] = deriveFeedStages(entries);
    expect(natural.count).toBe(3);
    expect(auction.count).toBe(1);
    expect(auction.metric?.value).toBe('25.0%'); // 1 of 4
    expect(mix.count).toBe(4);
  });
});

describe('deriveQueryStages', () => {
  it('returns null counts when the structured query is undefined', () => {
    expect(deriveQueryStages(undefined).map((s) => s.count)).toEqual([null, null, null, null]);
  });

  it('counts terms / intents / rewrites and embedding dimension', () => {
    const sq: StructuredQuery = {
      raw: 'sci fi',
      normalized: 'sci fi',
      terms: [
        { term: 'sci', weight: 1 },
        { term: 'fi', weight: 1 },
      ],
      intents: [{ category: 'Sci-Fi', score: 1 }],
      rewrites: ['science fiction', 'scifi', 'sf'],
      embedding: [0.1, 0.2, 0.3, 0.4],
    };
    const [terms, intents, rewrites, embedding] = deriveQueryStages(sq);
    expect(terms.count).toBe(2);
    expect(intents.count).toBe(1);
    expect(rewrites.count).toBe(3);
    expect(embedding.count).toBe(4);
    expect(embedding.metric?.value).toBe(4);
  });
});

describe('deriveAdStages', () => {
  it('counts distinct ad channels for a non-empty auction', () => {
    const ads: SponsoredAd[] = [
      {
        adId: 1,
        itemId: 10,
        advertiserId: 100,
        bidwordId: 5,
        title: 'ad',
        channel: 'KW_EXACT',
        bid: 1,
        quality: 1,
        relevance: 0.8,
        pctr: 0.1,
        pctrCalibrated: 0.09,
        ecpm: 2,
        chargedPrice: 1,
        position: 0,
        creativeId: 0,
        bidType: 'CPC',
      },
      {
        adId: 2,
        itemId: 11,
        advertiserId: 101,
        bidwordId: 6,
        title: 'ad2',
        channel: 'SEMANTIC_AD',
        bid: 1,
        quality: 1,
        relevance: 0.6,
        pctr: 0.2,
        pctrCalibrated: 0.18,
        ecpm: 3,
        chargedPrice: 2,
        position: 1,
        creativeId: 0,
        bidType: 'CPC',
      },
    ];
    const [recall] = deriveAdStages(ads);
    expect(recall.count).toBe(2);
    expect(recall.metric?.value).toBe(2); // distinct channels: KW_EXACT + SEMANTIC_AD
  });

  it('returns a null first-stage count for an empty auction', () => {
    expect(deriveAdStages([])[0].count).toBeNull();
  });
});
