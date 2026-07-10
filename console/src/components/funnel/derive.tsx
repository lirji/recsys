import {
  ClusterOutlined,
  DeploymentUnitOutlined,
  DollarOutlined,
  FilterOutlined,
  FunctionOutlined,
  LineChartOutlined,
  NodeIndexOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { FunnelStage } from './FunnelBand';
import { ACCENTS } from '../../theme/tokens';
import type { FeedEntry, RecommendItem, SponsoredAd, StructuredQuery } from '../../api/types';

// 把在线各链路的真实响应,派生成漏斗带的 stage 数组(纯函数)。
// 诚实标注:推荐/Feed 响应是「漏斗后的扁平列表」,API 无逐阶段真实候选数,故计数取响应能算出的口径
//(召回=去重通道数、结果=条数…),不虚构漏损。空响应 → count=null,漏斗静止。

const fx = (n: number, d = 3) => (Number.isFinite(n) ? n.toFixed(d) : '—');

// ── 推荐 / 搜索:召回 → 排序 → 重排/截断 ──
export function deriveRecStages(items: RecommendItem[]): FunnelStage[] {
  const has = items.length > 0;
  const channels = new Set(items.flatMap((i) => i.recallFrom)).size;
  const maxScore = items.reduce((m, i) => Math.max(m, i.score), 0);
  const topN = items.slice(0, Math.min(items.length, 10));
  const diversity = new Set(topN.flatMap((i) => i.recallFrom)).size;
  return [
    {
      key: 'recall',
      label: '召回 Recall',
      sub: '12 路多通道候选 · 合并去重 · 按路归一化',
      accent: ACCENTS.recall,
      icon: <ClusterOutlined />,
      count: has ? channels : null,
      metric: has ? { label: '结果', value: items.length } : null,
    },
    {
      key: 'rank',
      label: '排序 Rank',
      sub: 'ONNX / 规则打分 · recall×rank 融合 + 通道加成',
      accent: ACCENTS.rank,
      icon: <LineChartOutlined />,
      count: has ? items.length : null,
      metric: has ? { label: 'max 分', value: fx(maxScore) } : null,
    },
    {
      key: 'rerank',
      label: '重排 / 截断',
      sub: 'diversity / mmr / dpp 多样性 · top-N + 推荐理由',
      accent: ACCENTS.rerank,
      icon: <DeploymentUnitOutlined />,
      count: has ? items.length : null,
      metric: has ? { label: 'top-N 通道', value: diversity } : null,
    },
  ];
}

// ── 搜索广告:召回 → 相关性门槛 → pCTR 校准 → eCPM 竞价 → GSP 计费 ──
export function deriveAdStages(ads: SponsoredAd[]): FunnelStage[] {
  const has = ads.length > 0;
  const n = ads.length || 1;
  const channels = new Set(ads.map((a) => a.channel)).size;
  const avgRel = ads.reduce((s, a) => s + a.relevance, 0) / n;
  const avgPctr = ads.reduce((s, a) => s + a.pctr, 0) / n;
  const avgCalib = ads.reduce((s, a) => s + a.pctrCalibrated, 0) / n;
  const maxEcpm = ads.reduce((m, a) => Math.max(m, a.ecpm), 0);
  const sumCharged = ads.reduce((s, a) => s + a.chargedPrice, 0);
  return [
    {
      key: 'ad-recall',
      label: '广告召回',
      sub: '关键词倒排 / 语义 / U2A 多路',
      accent: ACCENTS.recall,
      icon: <ClusterOutlined />,
      count: has ? ads.length : null,
      metric: has ? { label: '通道', value: channels } : null,
    },
    {
      key: 'gate',
      label: '相关性门槛',
      sub: 'RelevanceGate 过滤低相关广告',
      accent: ACCENTS.rank,
      icon: <FilterOutlined />,
      count: has ? ads.length : null,
      metric: has ? { label: '平均相关性', value: fx(avgRel, 2) } : null,
    },
    {
      key: 'calib',
      label: 'pCTR 校准',
      sub: '复用排序模型 → 保序回归纠偏',
      accent: ACCENTS.rerank,
      icon: <FunctionOutlined />,
      count: has ? ads.length : null,
      metric: has ? { label: 'pCTR→校准', value: `${fx(avgPctr)}→${fx(avgCalib)}` } : null,
    },
    {
      key: 'ecpm',
      label: 'eCPM 竞价',
      sub: 'pacedBid·billFactor 排序 · oCPC 智能出价',
      accent: ACCENTS.ad,
      icon: <ThunderboltOutlined />,
      count: has ? ads.length : null,
      metric: has ? { label: 'max eCPM', value: fx(maxEcpm) } : null,
    },
    {
      key: 'gsp',
      label: 'GSP 计费',
      sub: '次价拍卖 · 实收 ≤ eCPM',
      accent: ACCENTS.gsp,
      icon: <DollarOutlined />,
      count: has ? ads.length : null,
      metric: has ? { label: 'Σ 实收', value: fx(sumCharged, 2) } : null,
    },
  ];
}

// ── 混排 Feed:自然推荐 → 广告竞价 → 混排 ──
export function deriveFeedStages(entries: FeedEntry[]): FunnelStage[] {
  const has = entries.length > 0;
  const adCount = entries.filter((e) => e.ad).length;
  const natCount = entries.length - adCount;
  const adLoad = entries.length ? (adCount / entries.length) * 100 : 0;
  return [
    {
      key: 'natural',
      label: '自然推荐',
      sub: '推荐漏斗产出的自然结果',
      accent: ACCENTS.recall,
      icon: <ClusterOutlined />,
      count: has ? natCount : null,
      metric: has ? { label: '占比', value: `${(100 - adLoad).toFixed(1)}%` } : null,
    },
    {
      key: 'ad-auction',
      label: '广告竞价',
      sub: 'eCPM / GSP · 频控 FrequencyCap',
      accent: ACCENTS.ad,
      icon: <DollarOutlined />,
      count: has ? adCount : null,
      metric: has ? { label: 'Ad Load', value: `${adLoad.toFixed(1)}%` } : null,
    },
    {
      key: 'mix',
      label: '混排',
      sub: 'Ad Load 位次 / 密度 · 去重带「赞助」标记',
      accent: ACCENTS.rerank,
      icon: <NodeIndexOutlined />,
      count: has ? entries.length : null,
      metric: has ? { label: '总条数', value: entries.length } : null,
    },
  ];
}

// ── Query 理解:分词 → 意图 → 改写 → 向量 ──
export function deriveQueryStages(sq: StructuredQuery | undefined): FunnelStage[] {
  const has = !!sq;
  const embDim = sq?.embedding?.length ?? 0;
  return [
    {
      key: 'terms',
      label: '分词',
      sub: '词法切分 + 词项权重',
      accent: ACCENTS.recall,
      icon: <FilterOutlined />,
      count: has ? sq!.terms.length : null,
      metric: null,
    },
    {
      key: 'intents',
      label: '意图',
      sub: '类目意图识别',
      accent: ACCENTS.rank,
      icon: <LineChartOutlined />,
      count: has ? sq!.intents.length : null,
      metric: null,
    },
    {
      key: 'rewrites',
      label: '改写',
      sub: 'LLM / 词法 query 改写',
      accent: ACCENTS.rerank,
      icon: <DeploymentUnitOutlined />,
      count: has ? sq!.rewrites.length : null,
      metric: null,
    },
    {
      key: 'embedding',
      label: '向量化',
      sub: '语义向量 · 供混合检索',
      accent: ACCENTS.gsp,
      icon: <FunctionOutlined />,
      count: has ? embDim : null,
      metric: has ? { label: '维度', value: embDim || '—' } : null,
    },
  ];
}
