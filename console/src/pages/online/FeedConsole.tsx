import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Input, InputNumber, Space, Statistic, Tag, Typography } from 'antd';
import { getFeed } from '../../api/feed';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import { useItemMeta } from '../../hooks/useItemMeta';
import { AppstoreOutlined } from '@ant-design/icons';
import RecallTags from '../../components/explain/RecallTags';
import FunnelBand from '../../components/funnel/FunnelBand';
import EmptyState from '../../components/EmptyState';
import PageHeader from '../../components/PageHeader';
import { deriveFeedStages } from '../../components/funnel/derive';
import { channelColor } from '../../components/explain/channelColors';
import { ACCENTS, BRAND, STATUS, hexOfPreset } from '../../theme/tokens';
import TracePanel from '../../components/explain/TracePanel';

export default function FeedConsole() {
  const { userId, scene } = useGlobalUser();
  const { message } = App.useApp();
  const [size, setSize] = useState(12);
  const [q, setQ] = useState('');

  const query = useQuery({
    queryKey: ['feed'],
    queryFn: () => getFeed({ q, userId, size, scene }),
    enabled: false,
  });

  const entries = query.data?.entries ?? [];
  const adCount = entries.filter((e) => e.ad).length;
  // 只给自然结果查元数据(广告标题走广告侧,feed entry 未携带);裸 #itemId 显示成真实标题。
  const itemMeta = useItemMeta(entries.filter((e) => !e.ad).map((e) => e.itemId));
  const stages = useMemo(() => deriveFeedStages(entries), [entries]);
  const flowing = !!query.data && !query.isFetching;

  const run = () => {
    query.refetch().then((r) => {
      if (r.data && r.data.entries.length === 0) message.info('feed 为空');
    });
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="混排 Feed 调试台"
        accent={ACCENTS.rerank}
        description="自然推荐 + 广告竞价按 Ad Load 位次/密度混排,带频控与「赞助」标记。"
      />
      <Card size="small" bordered={false}>
        <Space wrap>
          <span>size</span>
          <InputNumber min={1} max={100} value={size} onChange={(v) => v && setSize(v)} />
          <span>q(可选)</span>
          <Input allowClear value={q} onChange={(e) => setQ(e.target.value)} style={{ width: 200 }} onPressEnter={run} />
          <Button type="primary" loading={query.isFetching} onClick={run}>
            拉取混排 Feed
          </Button>
          <Typography.Text type="secondary">userId={userId} · scene={scene}</Typography.Text>
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

      <FunnelBand
        dense
        stages={stages}
        flowing={flowing}
        status={flowing ? { color: STATUS.online, label: '在线', pulse: true } : undefined}
      />

      <Card
        title={`Feed (${entries.length})`}
        extra={query.data ? <TracePanel traceId={query.data.traceId} requestId={query.data.requestId} raw={query.data} /> : null}
      >
        {query.isError ? (
          <Alert type="error" message={toApiError(query.error).message} showIcon />
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
    </Space>
  );
}
