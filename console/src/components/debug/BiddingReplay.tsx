import { useEffect, useState } from 'react';
import { Button, Space, Steps, Tag, Typography } from 'antd';
import {
  ClusterOutlined,
  DollarOutlined,
  FilterOutlined,
  FunctionOutlined,
  PauseOutlined,
  PlayCircleOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { SponsoredAd } from '../../api/types';
import { channelColor } from '../explain/channelColors';
import { ACCENTS, rgba } from '../../theme/tokens';
import { adPriceUnit } from '../../utils/adPricing';

// 使用响应快照解释召回、校准、排序和报价；不从报价推断已经发生资金效果。
// 自动播放尊重 prefers-reduced-motion，历史重放不会产生投放事件。

const f = (n: number, d = 4) => (Number.isFinite(n) ? n.toFixed(d) : '—');

interface Stage {
  title: string;
  accent: string;
  icon: React.ReactNode;
  desc: string;
  cell: (a: SponsoredAd) => React.ReactNode;
}

const STAGES: Stage[] = [
  {
    title: '广告召回',
    accent: ACCENTS.recall,
    icon: <ClusterOutlined />,
    desc: '关键词倒排 / 语义 / U2A 多路召回候选,带原始 出价·质量·相关性。',
    cell: (a) => `bid ${f(a.bid, 3)} · q ${f(a.quality, 2)} · rel ${f(a.relevance, 2)}`,
  },
  {
    title: '相关性门槛',
    accent: ACCENTS.rank,
    icon: <FilterOutlined />,
    desc: 'RelevanceGate 过滤低相关广告(下方展示的均已过门槛)。',
    cell: (a) => `相关性 ${f(a.relevance, 3)}`,
  },
  {
    title: 'pCTR 校准',
    accent: ACCENTS.rerank,
    icon: <FunctionOutlined />,
    desc: '复用排序模型出 pCTR,再经保序回归校准纠偏。',
    cell: (a) => {
      const down = a.pctrCalibrated < a.pctr;
      return (
        <span>
          {f(a.pctr)} <span style={{ color: down ? '#cf1322' : '#389e0d' }}>→ {f(a.pctrCalibrated)}</span>
        </span>
      );
    },
  },
  {
    title: 'eCPM 竞价',
    accent: ACCENTS.ad,
    icon: <ThunderboltOutlined />,
    desc: 'eCPM = pacedBid · billFactor,按 eCPM 排序决定位次。',
    cell: (a) => `eCPM ${f(a.ecpm)}`,
  },
  {
    title: '竞价定价',
    accent: ACCENTS.gsp,
    icon: <DollarOutlined />,
    desc: '竞价输出按计费事件给出的报价；排序分和报价单位不同，不能相减推断价差或已消费金额。',
    cell: (a) => {
      return (
        <span>
          计价单价 {f(a.chargedPrice)} {adPriceUnit(a.bidType)}
        </span>
      );
    },
  },
];

function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

export default function BiddingReplay({ ads }: { ads: SponsoredAd[] }) {
  const [step, setStep] = useState(0);
  const [playing, setPlaying] = useState(false);
  const reduce = prefersReducedMotion();

  useEffect(() => {
    if (!playing) return;
    const t = setInterval(() => {
      setStep((s) => {
        if (s >= STAGES.length - 1) {
          setPlaying(false);
          return s;
        }
        return s + 1;
      });
    }, 1500);
    return () => clearInterval(t);
  }, [playing]);

  const stage = STAGES[step];

  return (
    <div>
      <Steps
        size="small"
        current={step}
        onChange={setStep}
        style={{ marginBottom: 12 }}
        items={STAGES.map((s) => ({ title: s.title, icon: s.icon }))}
      />

      <Space wrap style={{ marginBottom: 12 }}>
        {!reduce && (
          <Button
            type="primary"
            icon={playing ? <PauseOutlined /> : <PlayCircleOutlined />}
            onClick={() => {
              if (step >= STAGES.length - 1) setStep(0);
              setPlaying((p) => !p);
            }}
          >
            {playing ? '暂停' : '播放'}
          </Button>
        )}
        <Button disabled={step === 0} onClick={() => setStep((s) => Math.max(0, s - 1))}>
          上一步
        </Button>
        <Button disabled={step >= STAGES.length - 1} onClick={() => setStep((s) => Math.min(STAGES.length - 1, s + 1))}>
          下一步
        </Button>
        <Typography.Text type="secondary">{stage.desc}</Typography.Text>
      </Space>

      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        {ads.map((a) => (
          <div
            key={a.adId}
            className="itc-row"
            style={{ borderLeft: `3px solid ${stage.accent}`, background: rgba(stage.accent, 0.03) }}
          >
            <div className="itc-rank" style={{ color: '#8a94a6', background: '#f2f4f8' }}>
              {a.position}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <Space size={8} wrap>
                <Typography.Text strong>{a.title || `#${a.itemId}`}</Typography.Text>
                <Tag color={channelColor(a.channel)}>{a.channel}</Tag>
              </Space>
            </div>
            <span
              className="mono"
              style={{ fontSize: 13, fontWeight: 600, color: stage.accent, minWidth: 200, textAlign: 'right' }}
            >
              {stage.cell(a)}
            </span>
          </div>
        ))}
      </Space>
    </div>
  );
}
