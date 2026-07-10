import { type CSSProperties, type ReactNode } from 'react';
import {
  AppstoreOutlined,
  ClockCircleOutlined,
  ClusterOutlined,
  DashboardOutlined,
  DeploymentUnitOutlined,
  DollarOutlined,
  ExperimentOutlined,
  LineChartOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import FunnelBand, { type FunnelStage } from './funnel/FunnelBand';
import { ACCENTS, STATUS, rgba } from '../theme/tokens';

// 系统总览页的「在线推荐漏斗」视觉。现薄封装通用 FunnelBand:保留自己健康态驱动的 header(kicker + 玻璃指标 chips),
// 漏斗流水线本体交给 FunnelBand。指标全部接真实 /api/console/system 数据,无写死假值。

// 组件对外契约:总览页把已拉取的 health/overview 派生成这些真实统计后传入。
export interface FunnelHeroStats {
  engineStatus?: string; // recsys-rec-engine 健康状态(UP/DOWN/UNKNOWN);undefined = 尚未探测
  healthLoading?: boolean; // 健康查询进行中 → 显示"检测中"
  liveApps: number; // 在线的常驻 HTTP 服务数(kind=app 且 status=UP)
  totalApps: number; // 常驻 HTTP 服务总数
  moduleCount: number; // 模块总数
  linkCount: number; // 核心链路数
  apiCount: number; // API 目录条数
  p99Ms?: number | null; // 推荐链路 P99 延迟(ms),来自 Prometheus;不可用/无流量为 null → 不显示
  qps?: number | null; // 推荐 QPS,来自 Prometheus;不可用/无流量为 null → 不显示
  adP99Ms?: number | null; // 广告链路 P99 延迟(ms),来自 Prometheus;不可用/无流量为 null → 不显示
}

const STAGES: Omit<FunnelStage, 'count' | 'metric'>[] = [
  { key: 'recall', label: '召回 Recall', sub: '12 路多通道 · 向量 / i2i / 双塔 / 生成式', icon: <ClusterOutlined />, accent: ACCENTS.recall },
  { key: 'rank', label: '排序 Rank', sub: 'DeepFM · MMoE · DIN · DIEN 序列建模', icon: <LineChartOutlined />, accent: ACCENTS.rank },
  { key: 'rerank', label: '重排 Rerank', sub: 'MMR · DPP 多样性 · 冷启动兴趣引导', icon: <DeploymentUnitOutlined />, accent: ACCENTS.rerank },
];

// 三个阶段都由 recsys-rec-engine 在线编排,故漏斗的"活/死"= rec-engine 健康。
type Live = { tone: 'online' | 'offline' | 'checking'; color: string; label: string };
function deriveLive(stats: FunnelHeroStats): Live {
  if (stats.healthLoading || stats.engineStatus === undefined) {
    return { tone: 'checking', color: STATUS.checking, label: '检测中' };
  }
  if (stats.engineStatus === 'UP') return { tone: 'online', color: STATUS.online, label: '在线' };
  if (stats.engineStatus === 'UNKNOWN') return { tone: 'checking', color: STATUS.checking, label: '未知' };
  return { tone: 'offline', color: STATUS.offline, label: '离线' };
}

export default function RecFunnelHero(stats: FunnelHeroStats) {
  const live = deriveLive(stats);
  const online = live.tone === 'online';
  const dot = (extra?: CSSProperties): ReactNode => (
    <span
      className={`fnl-dot${online ? ' fnl-dot--pulse' : ''}`}
      aria-hidden
      style={{ background: live.color, boxShadow: `0 0 10px 2px ${rgba(live.color, 0.7)}`, ...extra }}
    />
  );

  const kicker = (
    <span style={{ color: live.color, display: 'inline-flex', alignItems: 'center', gap: 8 }}>
      {dot()}
      在线链路 · PIPELINE {live.tone === 'online' ? 'ONLINE' : live.tone === 'offline' ? 'OFFLINE' : 'CHECKING'}
    </span>
  );

  const chips = (
    <>
      <span className="fnl-chip fnl-mono">
        {dot({ width: 7, height: 7 })}
        推荐编排 <b>{live.label}</b>
      </span>
      <span className="fnl-chip fnl-mono">
        <ThunderboltOutlined />
        在线服务 <b>{stats.totalApps === 0 ? '—' : `${stats.liveApps}/${stats.totalApps}`}</b>
      </span>
      <span className="fnl-chip fnl-mono">
        <AppstoreOutlined />
        模块 <b>{stats.moduleCount}</b>
      </span>
      <span className="fnl-chip fnl-mono">
        <ExperimentOutlined />
        链路 <b>{stats.linkCount}</b> · 接口 <b>{stats.apiCount}</b>
      </span>
      {stats.p99Ms != null && (
        <span className="fnl-chip fnl-mono" title="推荐链路 P99 延迟(Prometheus 实时)">
          <ClockCircleOutlined />
          推荐 P99 <b>{stats.p99Ms}ms</b>
        </span>
      )}
      {stats.qps != null && (
        <span className="fnl-chip fnl-mono" title="推荐 QPS(近 1 分钟速率)">
          <DashboardOutlined />
          QPS <b>{stats.qps}</b>
        </span>
      )}
      {stats.adP99Ms != null && (
        <span className="fnl-chip fnl-mono" title="广告链路 P99 延迟(Prometheus 实时)">
          <DollarOutlined />
          广告 P99 <b>{stats.adP99Ms}ms</b>
        </span>
      )}
    </>
  );

  const stages: FunnelStage[] = STAGES.map((s) => ({ ...s, count: null, metric: null }));

  return (
    <FunnelBand
      stages={stages}
      flowing={online}
      title="在线推荐漏斗"
      subtitle="毫秒级同步链路:多通道召回 → 模型排序 → 可插拔重排,融合竞价、计费与分层实验放量。"
      kicker={kicker}
      chips={chips}
      status={{ color: live.color, label: live.label, pulse: online }}
    />
  );
}
