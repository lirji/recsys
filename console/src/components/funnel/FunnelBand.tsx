import { Fragment, useEffect, useState, type CSSProperties, type ReactNode } from 'react';
import { DownOutlined } from '@ant-design/icons';
import { rgba } from '../../theme/tokens';
import { FUNNEL_CSS } from './funnelStyles';

// 通用「在线漏斗带」—— 深色发光 + 网格 + 阶段连接器 + 流动粒子。数据驱动:每个 stage 可绑真实计数/指标,
// 有数据时粒子才流动。系统总览 hero 与在线调试台各页共用此组件(见 RecFunnelHero / 各 Console)。

export interface FunnelStage {
  key: string;
  label: string; // 如 '召回 Recall'
  sub?: string; // 教学说明
  count?: number | null; // 绑真实数据的主计数;null / undefined = 无数据(静止)
  metric?: { label: string; value: string | number } | null; // 次级指标(如 max eCPM / avg rel)
  accent: string; // 强调色(ACCENTS.* 或 channelColor→hex)
  icon?: ReactNode;
}

export interface FunnelBandProps {
  stages: FunnelStage[];
  flowing?: boolean; // 是否流动(通常 = 有数据);reduced-motion 时内部再兜底关掉
  dense?: boolean; // console 紧凑模式 vs hero 宽松
  title?: string;
  subtitle?: string;
  kicker?: ReactNode; // 顶部状态行(hero 传入自带状态点的完整节点)
  chips?: ReactNode; // 右侧指标 chips(hero 复用)
  status?: { color: string; label: string; pulse?: boolean }; // 每个 stage 标题旁的小状态点
  /** 调试台默认收起,把结果表顶上折页;系统总览 hero 不传。 */
  collapsible?: boolean;
  defaultOpen?: boolean;
}

// 监听 prefers-reduced-motion —— 流点在 JS 侧也关掉(CSS 已隐藏,这里进一步免去无谓 DOM)。
function usePrefersReducedMotion(): boolean {
  const [reduce, setReduce] = useState(false);
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
    const on = () => setReduce(mq.matches);
    on();
    mq.addEventListener?.('change', on);
    return () => mq.removeEventListener?.('change', on);
  }, []);
  return reduce;
}

function fmtCount(v: number): string {
  if (!Number.isFinite(v)) return '—';
  return v >= 10000 ? `${(v / 1000).toFixed(1)}k` : String(v);
}

export default function FunnelBand({
  stages,
  flowing = false,
  dense = false,
  title,
  subtitle,
  kicker,
  chips,
  status,
  collapsible = false,
  defaultOpen = true,
}: FunnelBandProps) {
  const reduceMotion = usePrefersReducedMotion();
  const doFlow = flowing && !reduceMotion;
  const [open, setOpen] = useState(collapsible ? defaultOpen : true);
  const collapsed = collapsible && !open;

  const stageDot = (extra?: CSSProperties) => {
    if (!status) return null;
    return (
      <span
        className={`fnl-dot${status.pulse && flowing ? ' fnl-dot--pulse' : ''}`}
        aria-hidden
        style={{ background: status.color, boxShadow: `0 0 8px 1px ${rgba(status.color, 0.65)}`, ...extra }}
      />
    );
  };

  const showHead = Boolean(kicker || title || subtitle || chips);
  const summary = (
    <span className="fnl-summary fnl-mono">
      {stages.map((s, i) => (
        <Fragment key={s.key}>
          {i > 0 ? <span className="fnl-summary-sep">→</span> : null}
          <span className="fnl-summary-step">
            <span className="fnl-summary-label">{s.label}</span>
            {s.count != null ? (
              <span className="fnl-summary-count" style={{ color: s.accent }}>
                {fmtCount(s.count)}
              </span>
            ) : s.metric ? (
              <span className="fnl-summary-count" style={{ color: s.accent }}>
                {s.metric.value}
              </span>
            ) : null}
          </span>
        </Fragment>
      ))}
    </span>
  );

  return (
    <div className={`fnl-root${dense ? ' fnl-root--dense' : ''}${collapsed ? ' fnl-root--collapsed' : ''}`}>
      <style>{FUNNEL_CSS}</style>

      {collapsible ? (
        <button
          type="button"
          className="fnl-toggle"
          aria-expanded={open}
          onClick={() => setOpen((v) => !v)}
        >
          {summary}
          <DownOutlined className="fnl-chevron" aria-hidden />
        </button>
      ) : null}

      {collapsed ? null : (
        <>
      {showHead && (
        <div className="fnl-head">
          <div>
            {kicker ? <span className="fnl-kicker fnl-mono">{kicker}</span> : null}
            {title ? <div className="fnl-title">{title}</div> : null}
            {subtitle ? <div className="fnl-sub">{subtitle}</div> : null}
          </div>
          {chips ? <div className="fnl-metrics">{chips}</div> : null}
        </div>
      )}

      <div className="fnl-pipe" aria-hidden>
        {stages.map((s, i) => {
          const next = stages[i + 1];
          const hasCount = s.count != null;
          return (
            <Fragment key={s.key}>
              <div className="fnl-stage">
                <span
                  className="fnl-stage-ic"
                  style={{
                    color: s.accent,
                    background: rgba(s.accent, 0.14),
                    border: `1px solid ${rgba(s.accent, 0.42)}`,
                    boxShadow: `0 0 22px -3px ${rgba(s.accent, 0.55)}`,
                  }}
                >
                  {s.icon}
                </span>
                <div className="fnl-stage-body">
                  <div className="fnl-stage-head">
                    <span className="fnl-stage-title">{s.label}</span>
                    {stageDot({ width: 6, height: 6 })}
                  </div>
                  {hasCount ? (
                    <div className="fnl-mono" style={{ marginTop: 4 }}>
                      <span className="fnl-stage-count" style={{ color: s.accent }}>
                        {fmtCount(s.count as number)}
                      </span>
                      {s.metric ? (
                        <div className="fnl-stage-metric">
                          {s.metric.label} <b>{s.metric.value}</b>
                        </div>
                      ) : null}
                    </div>
                  ) : s.metric ? (
                    <div className="fnl-stage-metric" style={{ marginTop: 4 }}>
                      {s.metric.label} <b>{s.metric.value}</b>
                    </div>
                  ) : null}
                  {s.sub ? <div className="fnl-stage-sub">{s.sub}</div> : null}
                </div>
              </div>
              {next && (
                <div className="fnl-conn">
                  <span
                    className="fnl-conn-line"
                    style={{ background: `linear-gradient(90deg, ${rgba(s.accent, 0.6)}, ${rgba(next.accent, 0.6)})` }}
                  />
                  {doFlow &&
                    [0, 1, 2].map((d) => (
                      <span
                        key={d}
                        className="fnl-flow-dot"
                        style={{
                          background: next.accent,
                          boxShadow: `0 0 8px 2px ${rgba(next.accent, 0.75)}`,
                          animationDelay: `${d * 0.72}s`,
                        }}
                      />
                    ))}
                </div>
              )}
            </Fragment>
          );
        })}
      </div>
        </>
      )}
    </div>
  );
}
