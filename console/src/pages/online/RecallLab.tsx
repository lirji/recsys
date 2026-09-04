import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Col, Input, InputNumber, Row, Select, Space } from 'antd';
import {
  ApartmentOutlined,
  CheckCircleOutlined,
  DatabaseOutlined,
  NodeIndexOutlined,
  PartitionOutlined,
} from '@ant-design/icons';
import { getRecommend } from '../../api/recommend';
import { queryKeys } from '../../api/queryKeys';
import { toApiError } from '../../api/client';
import { useDebugParams } from '../../hooks/useDebugParams';
import { useItemMeta } from '../../hooks/useItemMeta';
import { RECALL_CHANNELS, decodeRecallChannels, encodeRecallChannels } from '../../recall/channels';
import PageHeader from '../../components/PageHeader';
import DebugField from '../../components/debug/DebugField';
import EmptyState from '../../components/EmptyState';
import StatCard from '../../components/StatCard';
import { ResultRowsSkeleton } from '../../components/Skeletons';
import ItemCard from '../../components/explain/ItemCard';
import TracePanel from '../../components/explain/TracePanel';
import ChannelBreakdown, { deriveChannelStats } from '../../components/explain/ChannelBreakdown';
import ChannelFilterBar from '../../components/explain/ChannelFilterBar';
import { ACCENTS } from '../../theme/tokens';

type Params = { userId: number; size: number; scene: string; q: string; recallChannels: string };

// 召回通道沙盘:把推荐链路的召回阶段拆成 12 路逐通道可观测 —— 每路召回多少、去重贡献多少、
// 最终存活多少、哪些 item 被多路协同命中。复用 ?explain=true 的 channelRecall/channelContribution + recallFrom。
// recallChannels 非空时走后端调试覆盖,只跑指定通道(对齐 eval --recall-only)。
export default function RecallLab() {
  const { initial, applied, setApplied, userId, scene } = useDebugParams<Params>({
    userId: 1,
    size: 30,
    scene: 'feed',
    q: '',
    recallChannels: '',
  });
  const [size, setSize] = useState(initial.size);
  const [q, setQ] = useState(initial.q);
  const [channels, setChannels] = useState<string[]>(decodeRecallChannels(initial.recallChannels));
  const [selectedChannel, setSelectedChannel] = useState<string | null>(null);

  const query = useQuery({
    queryKey: queryKeys.recallLab(applied),
    queryFn: () =>
      getRecommend({
        userId: applied.userId,
        size: applied.size,
        scene: applied.scene,
        q: applied.q,
        explain: true,
        recallChannels: applied.recallChannels || undefined,
      }),
  });

  const run = () => {
    setSelectedChannel(null);
    setApplied({ userId, size, scene, q, recallChannels: encodeRecallChannels(channels) });
  };

  const items = query.data?.items ?? [];
  const explain = query.data?.explain ?? null;
  const stats = useMemo(() => deriveChannelStats(explain, items), [explain, items]);
  const maxScore = items.reduce((m, it) => Math.max(m, it.score), 0);
  const itemMeta = useItemMeta(items.map((it) => it.itemId));

  const liveChannels = stats.filter((s) => s.rawCount > 0).length;
  const multiHit = items.filter((it) => new Set(it.recallFrom).size >= 2).length;
  const rawTotal = stats.reduce((s, c) => s + c.rawCount, 0);

  const shownItems = selectedChannel ? items.filter((it) => it.recallFrom.includes(selectedChannel)) : items;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="召回通道沙盘"
        accent={ACCENTS.recall}
        description="指定通道召回,或全量后再按通道过滤。"
        extra={query.data ? <TracePanel traceId={query.data.traceId} raw={query.data} /> : null}
      />

      <Card size="small" bordered={false}>
        <Space wrap size={[16, 8]}>
          <DebugField label="条数">
            <InputNumber min={1} max={200} value={size} onChange={(v) => v && setSize(v)} />
          </DebugField>
          <DebugField label="查询词">
            <Input
              allowClear
              placeholder="可选"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              style={{ width: 240 }}
              onPressEnter={run}
            />
          </DebugField>
          <DebugField label="召回通道">
            <Select
              mode="multiple"
              allowClear
              placeholder="空=实验分桶全量"
              value={channels}
              onChange={setChannels}
              style={{ minWidth: 280 }}
              options={RECALL_CHANNELS.map((c) => ({ value: c, label: c }))}
            />
          </DebugField>
          <Button type="primary" icon={<PartitionOutlined />} loading={query.isFetching} onClick={run}>
            分解召回
          </Button>
        </Space>
      </Card>

      {query.isError ? (
        <Alert type="error" message={toApiError(query.error).message} showIcon />
      ) : query.isFetching && !query.data ? (
        <ResultRowsSkeleton rows={6} />
      ) : items.length === 0 ? (
        <EmptyState
          icon={<PartitionOutlined />}
          accent={ACCENTS.recall}
          title="暂无召回结果"
          description="检查是否已灌数据 / 向量,或 rec-engine 是否启动。"
          action={
            <Button type="primary" onClick={run}>
              分解召回
            </Button>
          }
        />
      ) : (
        <>
          <Row gutter={[16, 16]}>
            <Col xs={12} sm={12} lg={6}>
              <StatCard title="命中通道" value={liveChannels} icon={<ApartmentOutlined />} accent={ACCENTS.recall} suffix="路" />
            </Col>
            <Col xs={12} sm={12} lg={6}>
              <StatCard title="去重前原始召回" value={rawTotal} icon={<DatabaseOutlined />} accent={ACCENTS.rank} suffix="条" />
            </Col>
            <Col xs={12} sm={12} lg={6}>
              <StatCard title="最终结果" value={items.length} icon={<CheckCircleOutlined />} accent={ACCENTS.rerank} suffix="条" />
            </Col>
            <Col xs={12} sm={12} lg={6}>
              <StatCard title="多路协同命中" value={multiHit} icon={<NodeIndexOutlined />} accent={ACCENTS.gsp} suffix="条" />
            </Col>
          </Row>

          <Card title="逐通道分解">
            <ChannelBreakdown stats={stats} selectedChannel={selectedChannel} onSelectChannel={setSelectedChannel} />
          </Card>

          <Card
            title={`结果明细 (${shownItems.length}${selectedChannel ? ` / ${items.length}` : ''})`}
            extra={
              selectedChannel ? (
                <Button size="small" onClick={() => setSelectedChannel(null)}>
                  清除过滤
                </Button>
              ) : null
            }
          >
            <Space direction="vertical" size={14} style={{ width: '100%' }}>
              <ChannelFilterBar stats={stats} selectedChannel={selectedChannel} onSelectChannel={setSelectedChannel} />
              {shownItems.length === 0 ? (
                <EmptyState
                  icon={<PartitionOutlined />}
                  accent={ACCENTS.warn}
                  title={`通道 ${selectedChannel} 无存活结果`}
                  description="该路有原始召回,但去重/排序/重排后未有 item 进入最终结果。点「全部」还原。"
                />
              ) : (
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  {shownItems.map((it, i) => (
                    <ItemCard
                      key={it.itemId}
                      item={it}
                      rank={i + 1}
                      maxScore={maxScore}
                      meta={itemMeta(it.itemId)}
                      breakdown={explain?.scores?.[it.itemId]}
                    />
                  ))}
                </Space>
              )}
            </Space>
          </Card>
        </>
      )}
    </Space>
  );
}
