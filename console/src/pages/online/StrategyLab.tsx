import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Col, Input, InputNumber, Row, Select, Space, Tag, Typography } from 'antd';
import { DiffOutlined } from '@ant-design/icons';
import { getRecommend } from '../../api/recommend';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import { useUrlParams } from '../../hooks/useUrlParams';
import { useItemMeta } from '../../hooks/useItemMeta';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { ResultRowsSkeleton } from '../../components/Skeletons';
import FunnelBand from '../../components/funnel/FunnelBand';
import { deriveRecStages } from '../../components/funnel/derive';
import ResultDiff from '../../components/debug/ResultDiff';
import { ACCENTS, STATUS, rgba } from '../../theme/tokens';
import type { RecommendItem } from '../../api/types';

// 排序策略(对齐 RANK_STRATEGY / RankRouter);未知策略后端回退规则打分。
const RANK_STRATEGIES = ['v1', 'onnx', 'deepfm', 'dcn', 'mmoe', 'ple', 'din', 'dien', 'sim'];
// 重排策略(对齐 RerankRouter);未知回退 diversity。
const RERANK_STRATEGIES = ['diversity', 'mmr', 'dpp', 'none'];

type Params = {
  userId: number;
  size: number;
  scene: string;
  q: string;
  aRank: string;
  aRerank: string;
  bRank: string;
  bRerank: string;
};

const sideLabel = (rank: string, rerank: string) => `rank=${rank} · rerank=${rerank}`;

// 策略对比台:固定 (userId,size,q),让 A/B 两侧各走不同 rank×rerank 策略并排 diff。
// 依赖 /api/recommend 的策略覆盖参数(rankStrategy/rerankStrategy),绕过实验分桶、跳过冷启动,使对比确定可复现。
export default function StrategyLab() {
  const { userId, scene, setUserId, setScene } = useGlobalUser();
  const { initial, write } = useUrlParams<Params>({
    userId,
    size: 10,
    scene,
    q: '',
    aRank: 'onnx',
    aRerank: 'diversity',
    bRank: 'din',
    bRerank: 'diversity',
  });
  const [size, setSize] = useState(initial.size);
  const [q, setQ] = useState(initial.q);
  const [aRank, setARank] = useState(initial.aRank);
  const [aRerank, setARerank] = useState(initial.aRerank);
  const [bRank, setBRank] = useState(initial.bRank);
  const [bRerank, setBRerank] = useState(initial.bRerank);
  const [applied, setApplied] = useState<Params>(initial);

  useEffect(() => {
    if (initial.userId !== userId) setUserId(initial.userId);
    if (initial.scene !== scene) setScene(initial.scene);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    write(applied);
  }, [applied, write]);

  const queryA = useQuery({
    queryKey: ['strategy-lab', 'A', applied.userId, applied.size, applied.scene, applied.q, applied.aRank, applied.aRerank],
    queryFn: () =>
      getRecommend({
        userId: applied.userId,
        size: applied.size,
        scene: applied.scene,
        q: applied.q,
        explain: true,
        rankStrategy: applied.aRank,
        rerankStrategy: applied.aRerank,
      }),
  });
  const queryB = useQuery({
    queryKey: ['strategy-lab', 'B', applied.userId, applied.size, applied.scene, applied.q, applied.bRank, applied.bRerank],
    queryFn: () =>
      getRecommend({
        userId: applied.userId,
        size: applied.size,
        scene: applied.scene,
        q: applied.q,
        explain: true,
        rankStrategy: applied.bRank,
        rerankStrategy: applied.bRerank,
      }),
  });

  const run = () => setApplied({ userId, size, scene, q, aRank, aRerank, bRank, bRerank });

  const aItems = queryA.data?.items ?? [];
  const bItems = queryB.data?.items ?? [];
  const stagesA = useMemo(() => deriveRecStages(aItems, queryA.data?.explain), [aItems, queryA.data]);
  const stagesB = useMemo(() => deriveRecStages(bItems, queryB.data?.explain), [bItems, queryB.data]);
  const itemMeta = useItemMeta([...aItems, ...bItems].map((it) => it.itemId));

  const fetching = queryA.isFetching || queryB.isFetching;
  const anyError = queryA.isError || queryB.isError;
  const bothReady = !!queryA.data && !!queryB.data;

  // 一侧策略选择器 + 漏斗。选择器从「塞进 Card 标题」重做成标签 + 全宽下拉的对齐网格,A/B 两侧视觉对称。
  const sideCard = (opts: {
    side: 'A' | 'B';
    accent: string;
    rank: string;
    setRank: (v: string) => void;
    rerank: string;
    setRerank: (v: string) => void;
    appliedRank: string;
    appliedRerank: string;
    stages: ReturnType<typeof deriveRecStages>;
    items: RecommendItem[];
    isFetching: boolean;
  }) => {
    const { side, accent, rank, setRank, rerank, setRerank, appliedRank, appliedRerank, stages, items, isFetching } =
      opts;
    const online = items.length > 0 && !isFetching;
    // 选择器已改但尚未点「对比」→ 漏斗仍是旧数据,给出「待对比」提示。
    const dirty = rank !== appliedRank || rerank !== appliedRerank;

    const sel = (
      label: string,
      value: string,
      onChange: (v: string) => void,
      list: string[],
      accented: boolean,
    ) => (
      <>
        <span style={{ fontSize: 13, color: '#6b7280' }}>{label}</span>
        <Select
          value={value}
          onChange={onChange}
          style={{ width: '100%' }}
          options={list.map((s) => ({ value: s, label: s }))}
          {...(accented ? { className: 'mono' } : {})}
        />
      </>
    );

    return (
      <Card
        size="small"
        style={{ height: '100%', borderTop: `2px solid ${accent}` }}
        title={
          <Space size={8}>
            <Tag color={side === 'A' ? 'blue' : 'purple'} style={{ marginInlineEnd: 0 }}>
              {side}
            </Tag>
            <span style={{ fontWeight: 600 }}>方案 {side}</span>
          </Space>
        }
        extra={
          <Space size={8}>
            {dirty ? <Tag color="orange">待对比</Tag> : null}
            <Typography.Text type="secondary" className="mono">
              {items.length} 条
            </Typography.Text>
          </Space>
        }
      >
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: '48px 1fr',
              alignItems: 'center',
              columnGap: 12,
              rowGap: 10,
              padding: '12px 14px',
              borderRadius: 10,
              background: rgba(accent, 0.05),
              border: `1px solid ${rgba(accent, 0.14)}`,
            }}
          >
            {sel('排序', rank, setRank, RANK_STRATEGIES, true)}
            {sel('重排', rerank, setRerank, RERANK_STRATEGIES, false)}
          </div>
          <FunnelBand
            dense
            stages={stages}
            flowing={online}
            status={online ? { color: STATUS.online, label: '在线', pulse: true } : undefined}
          />
        </Space>
      </Card>
    );
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="策略对比台"
        accent={ACCENTS.rank}
        description="同一 (userId,size,q) 下切换 A/B 两套 排序×重排 策略并排对比:漏斗、名次升降、新增/掉出、top-K 重合。绕过实验分桶,结果确定可复现。"
      />

      <Card size="small" bordered={false}>
        <Space wrap size={[16, 8]}>
          <Space size={8}>
            <Typography.Text type="secondary">size</Typography.Text>
            <InputNumber min={1} max={100} value={size} onChange={(v) => v && setSize(v)} />
          </Space>
          <Space size={8}>
            <Typography.Text type="secondary">q(可选)</Typography.Text>
            <Input
              allowClear
              placeholder="留空=纯个性化推荐"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              style={{ width: 220 }}
              onPressEnter={run}
            />
          </Space>
          <Button type="primary" icon={<DiffOutlined />} loading={fetching} onClick={run}>
            对比
          </Button>
          <Typography.Text type="secondary" className="mono">
            userId={userId} · scene={scene}
          </Typography.Text>
        </Space>
      </Card>

      <Row gutter={[16, 16]} align="stretch">
        <Col xs={24} lg={12}>
          {sideCard({
            side: 'A',
            accent: ACCENTS.recall,
            rank: aRank,
            setRank: setARank,
            rerank: aRerank,
            setRerank: setARerank,
            appliedRank: applied.aRank,
            appliedRerank: applied.aRerank,
            stages: stagesA,
            items: aItems,
            isFetching: queryA.isFetching,
          })}
        </Col>
        <Col xs={24} lg={12}>
          {sideCard({
            side: 'B',
            accent: ACCENTS.rerank,
            rank: bRank,
            setRank: setBRank,
            rerank: bRerank,
            setRerank: setBRerank,
            appliedRank: applied.bRank,
            appliedRerank: applied.bRerank,
            stages: stagesB,
            items: bItems,
            isFetching: queryB.isFetching,
          })}
        </Col>
      </Row>

      <Card title="A / B 结果对比">
        {anyError ? (
          <Alert type="error" showIcon message={toApiError(queryA.error ?? queryB.error).message} />
        ) : fetching && !bothReady ? (
          <ResultRowsSkeleton rows={6} />
        ) : !bothReady ? (
          <EmptyState
            icon={<DiffOutlined />}
            accent={ACCENTS.rank}
            title="选好两侧策略后点「对比」"
            description="A/B 两侧走各自的 rank×rerank 策略,下面按 itemId 对齐出名次升降与增减。"
            action={
              <Button type="primary" onClick={run}>
                对比
              </Button>
            }
          />
        ) : (
          <ResultDiff<RecommendItem>
            aLabel={sideLabel(applied.aRank, applied.aRerank)}
            bLabel={sideLabel(applied.bRank, applied.bRerank)}
            aItems={aItems}
            bItems={bItems}
            keyOf={(t) => t.itemId}
            primary={(t) => {
              const m = itemMeta(t.itemId);
              return m?.title ? (
                <Space size={6}>
                  <Typography.Text ellipsis style={{ maxWidth: 220 }}>
                    {m.title}
                  </Typography.Text>
                  <Typography.Text type="secondary" className="mono" style={{ fontSize: 12 }}>
                    #{t.itemId}
                  </Typography.Text>
                </Space>
              ) : (
                <span className="mono">#{t.itemId}</span>
              );
            }}
            columns={[{ label: '分数', get: (t) => t.score, fmt: (n) => n.toFixed(4) }]}
          />
        )}
      </Card>
    </Space>
  );
}
