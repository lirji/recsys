import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Input, InputNumber, Space, Statistic, Tag, Typography } from 'antd';
import { getFeed } from '../../api/feed';
import { queryKeys } from '../../api/queryKeys';
import { toApiError } from '../../api/client';
import { useDebugParams } from '../../hooks/useDebugParams';
import { useItemMeta } from '../../hooks/useItemMeta';
import { AppstoreOutlined } from '@ant-design/icons';
import RecallTags from '../../components/explain/RecallTags';
import FunnelBand from '../../components/funnel/FunnelBand';
import EmptyState from '../../components/EmptyState';
import PageHeader from '../../components/PageHeader';
import DebugField from '../../components/debug/DebugField';
import { deriveFeedStages } from '../../components/funnel/derive';
import { channelColor } from '../../components/explain/channelColors';
import { ACCENTS, BRAND, STATUS, hexOfPreset } from '../../theme/tokens';
import TracePanel from '../../components/explain/TracePanel';

type FeedParams = { userId: number; size: number; scene: string; q: string };

export default function FeedConsole() {
  const { applied, setApplied, userId, scene } = useDebugParams<FeedParams>({
    userId: 1,
    size: 12,
    scene: 'feed',
    q: '',
  });
  const [size, setSize] = useState(applied.size);
  const [q, setQ] = useState(applied.q);

  const query = useQuery({
    queryKey: queryKeys.feed(applied),
    queryFn: () => getFeed(applied),
  });

  const entries = query.data?.entries ?? [];
  const adCount = entries.filter((e) => e.ad).length;
  // 只给自然结果查元数据(广告标题走广告侧,feed entry 未携带);裸 #itemId 显示成真实标题。
  const itemMeta = useItemMeta(entries.filter((e) => !e.ad).map((e) => e.itemId));
  const stages = useMemo(() => deriveFeedStages(entries), [entries]);
  const flowing = !!query.data && !query.isFetching;

  const run = () => {
    setApplied({ userId, size, scene, q });
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="混排 Feed"
        accent={ACCENTS.rerank}
        description="自然结果与广告按位次混排。"
      />
      <Card size="small" bordered={false}>
        <Space wrap>
          <DebugField label="条数">
            <InputNumber min={1} max={100} value={size} onChange={(v) => v && setSize(v)} />
          </DebugField>
          <DebugField label="查询词">
            <Input allowClear placeholder="可选" value={q} onChange={(e) => setQ(e.target.value)} style={{ width: 200 }} onPressEnter={run} />
          </DebugField>
          <Button type="primary" loading={query.isFetching} onClick={run}>
            拉取混排 Feed
          </Button>
        </Space>
      </Card>

      <Space size={24} wrap>
        <Statistic title="总条数" value={entries.length} />
        <Statistic title="广告条数" value={adCount} valueStyle={{ color: '#d48806' }} />
        <Statistic
          title="Ad Load"
          value={entries.length ? (adCount / entries.length) * 100 : 0}
          precision={1}
          suffix="%"
        />
      </Space>

      <Card
        title={`Feed (${entries.length})`}
        extra={query.data ? <TracePanel traceId={query.data.traceId} requestId={query.data.requestId} raw={query.data} /> : null}
      >
        {query.isError ? (
          <Alert type="error" message={toApiError(query.error).message} showIcon />
        ) : query.isFetching && !query.data ? (
          <Typography.Text type="secondary">加载中…</Typography.Text>
        ) : entries.length === 0 ? (
          <EmptyState
            icon={<AppstoreOutlined />}
            accent={ACCENTS.rerank}
            title="暂无混排 Feed"
            description="检查自然推荐与广告是否就绪。"
            action={
              <Button type="primary" onClick={run}>
                拉取混排 Feed
              </Button>
            }
          />
        ) : (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {entries.map((e) => (
              <div
                key={`${e.position}-${e.ad ? 'ad' : 'nat'}-${e.ad ? e.adId : e.itemId}`}
                className="itc-row"
                style={{
                  background: e.ad ? '#fffbe6' : '#fff',
                  borderLeft: `3px solid ${
                    e.ad ? hexOfPreset('gold') : e.recallFrom.length ? hexOfPreset(channelColor(e.recallFrom[0])) : BRAND
                  }`,
                }}
              >
                <div className="itc-rank" style={{ color: '#8a94a6', background: '#f2f4f8' }}>
                  {e.position}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <Space size={8} wrap>
                    {e.ad ? <Tag color="gold">赞助</Tag> : null}
                    {!e.ad && itemMeta(e.itemId)?.title ? (
                      <>
                        <Typography.Text strong ellipsis style={{ maxWidth: 260 }}>
                          {itemMeta(e.itemId)!.title}
                        </Typography.Text>
                        <Typography.Text type="secondary" className="mono" style={{ fontSize: 12 }}>
                          #{e.itemId}
                        </Typography.Text>
                      </>
                    ) : (
                      <Typography.Text strong className="mono">
                        {e.ad ? `ad #${e.adId}` : `#${e.itemId}`}
                      </Typography.Text>
                    )}
                    <RecallTags channels={e.recallFrom} />
                  </Space>
                  {e.reason ? <div style={{ color: '#666', fontSize: 13, marginTop: 2 }}>{e.reason}</div> : null}
                </div>
                <span className="mono" style={{ fontSize: 12, color: '#666' }}>score {e.score.toFixed(4)}</span>
              </div>
            ))}
          </Space>
        )}
      </Card>

      <FunnelBand
        dense
        collapsible
        defaultOpen={false}
        stages={stages}
        flowing={flowing}
        status={flowing ? { color: STATUS.online, label: '在线', pulse: true } : undefined}
      />
    </Space>
  );
}
