// 后端 DTO 的 TS 镜像。字段名/顺序严格对齐 recsys-common 与 recsys-advertiser 的 record 定义。

// ===== 在线推荐 / 搜索 =====
export interface RecommendItem {
  itemId: number;
  score: number;
  recallFrom: string[];
  reason: string;
}
export interface RecommendResponse {
  userId: number;
  scene: string;
  items: RecommendItem[];
  traceId: string;
  explain?: RecommendExplain | null; // 仅 explain=true 请求返回;否则 null
}

// ===== 在线链路 explain(?explain=true 时后端返回的真实逐阶段计数 / 打分分解) =====
export interface FunnelStageCount {
  name: string; // recall/filter/preRank/rank/fusion/rerank
  in: number;
  out: number;
}
export interface ChannelRecall {
  channel: string;
  rawCount: number; // 去重前该路原始召回条数
}
export interface ChannelContribution {
  channel: string;
  count: number; // 去重后该路对候选池的贡献条数
}
export interface ScoreBreakdown {
  rNorm: number;
  rankScore: number;
  ftrl: number;
  bandit: number;
  base: number;
  boost: number;
  persBoost: number;
  debias: number;
  finalScore: number;
}
export interface RecommendExplain {
  stages: FunnelStageCount[];
  channelRecall: ChannelRecall[];
  channelContribution: ChannelContribution[];
  scores: Record<string, ScoreBreakdown>; // itemId(字符串键) → 打分分解
}

// ===== 物料元数据(/api/console/items,把裸 #itemId 显示成真实标题/类目) =====
export interface ItemMeta {
  itemId: number;
  title: string;
  category: string;
  tags: string[];
}

// ===== 搜索广告 =====
export type AdChannel = 'KW_EXACT' | 'KW_BROAD' | 'SEMANTIC_AD' | 'U2A' | 'HOT_AD';
export interface SponsoredAd {
  adId: number;
  itemId: number;
  advertiserId: number;
  bidwordId: number;
  title: string;
  channel: AdChannel;
  bid: number;
  quality: number;
  relevance: number;
  pctr: number;
  pctrCalibrated: number;
  ecpm: number;
  chargedPrice: number;
  position: number;
  creativeId: number;
  bidType: string; // CPC/OCPC/CPM/OCPM/CPA
}
export interface SearchAdsResponse {
  userId: number;
  query: string;
  requestId: string;
  ads: SponsoredAd[];
  traceId: string;
}

// ===== 混排 Feed =====
export interface FeedEntry {
  ad: boolean;
  itemId: number;
  adId: number;
  position: number;
  score: number;
  reason: string;
  recallFrom: string[];
}
export interface BlendedFeedResponse {
  userId: number;
  query: string;
  requestId: string;
  entries: FeedEntry[];
  traceId: string;
}

// ===== Query 理解 =====
export interface TermWeight {
  term: string;
  weight: number;
}
export interface CategoryScore {
  category: string;
  score: number;
}
export interface StructuredQuery {
  raw: string;
  normalized: string;
  terms: TermWeight[];
  intents: CategoryScore[];
  rewrites: string[];
  embedding: number[] | null;
  hasEmbedding?: boolean;
}

// ===== 行为上报 =====
export type ActionType = 'IMPRESSION' | 'CLICK' | 'LIKE' | 'PLAY' | 'RATING';
export interface BehaviorEvent {
  userId: number;
  itemId: number;
  action: ActionType;
  value: number;
  scene: string;
  bucket?: string | null;
  ts: number;
}

// ===== 实验管理 =====
export interface ExperimentLayer {
  salt?: string;
  variants: Record<string, number>;
}
export interface ExperimentSnapshot {
  staticEnabled: boolean;
  staticLayers: Record<string, ExperimentLayer>;
  overrides: Record<string, unknown>;
}

// ===== 广告主后台 =====
export interface AdvertiserView {
  advertiserId: number;
  name: string;
  dailyBudget: number;
  status: string;
  spentToday: number;
  remainingBudget: number;
}
export interface AdvertiserUpsert {
  name: string;
  dailyBudget?: number | null;
  status?: string | null;
}

export interface BidwordView {
  id: number;
  adId: number;
  keyword: string;
  matchType: string;
  bid: number;
  bidMode: string;
}
export interface BidwordUpsert {
  keyword: string;
  matchType?: string; // EXACT/PHRASE/BROAD
  bid?: number | null;
  bidMode?: string; // CPC/oCPC/oCPM
}

export interface CreativeView {
  creativeId: number;
  adId: number;
  title: string;
  landingUrl: string;
  status: string;
}
export interface CreativeUpsert {
  title: string;
  landingUrl?: string;
  status?: string;
}

export interface AdView {
  adId: number;
  advertiserId: number;
  itemId: number;
  title: string;
  landingUrl: string;
  qualityScore: number;
  status: string;
  optimizationType: string;
  targetCpa?: number | null;
  hasEmbedding: boolean;
  bidwords: BidwordView[];
  creatives: CreativeView[];
}
export interface AdUpsert {
  itemId?: number | null;
  title: string;
  landingUrl?: string;
  qualityScore?: number | null;
  status?: string;
  optimizationType?: string; // CPC/OCPC
  targetCpa?: number | null;
  bidwords?: BidwordUpsert[];
}

export interface AdReportRow {
  adId: number;
  title: string;
  impressions: number;
  clicks: number;
  conversions: number;
  spend: number;
  ctr: number;
  cvr: number;
  ecpm: number;
}

// ===== 离线报表(recsys-web 的 /api/console/report)=====
export type ReportCategory =
  | 'eval'
  | 'ab-report'
  | 'ad-report'
  | 'data-quality'
  | 'ad-quality'
  | 'other';
export interface ReportFileInfo {
  category: ReportCategory;
  fileName: string;
  timestamp: string;
  sizeBytes: number;
  modifiedAt: number;
}
export interface ReportTable {
  fileName: string;
  category: ReportCategory;
  columns: string[];
  rows: string[][];
}

// ===== 系统总览(recsys-console 的 /api/console/system)=====
export interface SystemModule {
  name: string;
  type: 'frontend' | 'app' | 'job' | 'lib' | string;
  description: string;
  port?: number | null;
  grpcPort?: number | null;
  gatewayPrefixes: string[];
  dependencies: string[];
  runnable: boolean;
  frontendPath?: string | null;
}

export interface SystemLink {
  name: string;
  description: string;
  steps: string[];
  frontendPath?: string | null;
}

export interface SystemApiEndpoint {
  method: string;
  path: string;
  service: string;
  description: string;
  frontendPath?: string | null;
}

export interface SystemCommandItem {
  label: string;
  command: string;
}

export interface SystemCommandGroup {
  name: string;
  items: SystemCommandItem[];
}

export interface ServiceHealth {
  service: string;
  name: string;
  kind: string;
  url?: string | null;
  status: string;
  message: string;
  checkedAt: number;
}

export interface SystemOverview {
  projectName: string;
  description: string;
  stack: string;
  modules: SystemModule[];
  links: SystemLink[];
  apis: SystemApiEndpoint[];
  commands: SystemCommandGroup[];
}

// 来自 Prometheus 的真实实时指标(/api/console/system/metrics);观测栈未起时 available=false、各值为 null。
export interface SystemMetrics {
  available: boolean;
  source: string;
  message: string;
  recommendP99Ms: number | null;
  recommendAvgMs: number | null;
  recommendQps: number | null;
  adP99Ms: number | null;
  checkedAt: number;
}

// ===== 用户360(/api/console/user/{id}) =====
export interface UserBehaviorRow {
  itemId: number;
  action: string;
  ts: number;
  bucket: string | null;
  position: number | null;
  scene: string | null;
  value: number | null;
}
export interface UserStats {
  totalInteractions: number;
  actionCounts: Record<string, number>;
  distinctCategories: number;
  bucketsSeen: string[];
}
export interface UserRealtime {
  available: boolean;
  rtCategoryPrefs: Record<string, number>;
  recentSeqItems: number[];
  recCached: boolean;
  seenCount: number;
}
export interface UserProfileView {
  userId: number;
  exists: boolean;
  profileJson: string | null;
  profileUpdatedAt: number | null;
  hasEmbedding: boolean;
  stats: UserStats;
  recentBehavior: UserBehaviorRow[];
  realtime: UserRealtime;
}

// ===== 一键诊断(/api/console/diagnosis) =====
export interface DiagnosisCheck {
  key: string;
  name: string;
  status: string; // PASS / WARN / FAIL / INFO
  detail: string;
}
export interface DiagnosisReport {
  overall: string; // PASS / WARN / FAIL
  checks: DiagnosisCheck[];
  checkedAt: number;
}

// ===== 告警面板(/api/console/alerts) =====
export interface AlertItem {
  level: string; // INFO / WARN / ERROR
  source: string;
  message: string;
  ts: number;
}
