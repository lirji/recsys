import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Input, InputNumber, Space, Statistic, Tag, Typography } from 'antd';
import { getFeed } from '../../api/feed';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import RecallTags from '../../components/explain/RecallTags';
import PipelineSteps from '../../components/explain/PipelineSteps';
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

  const run = () => {
    query.refetch().then((r) => {
      if (r.data && r.data.entries.length === 0) message.info('feed 为空');
    });
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
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

      <Card size="small" title="混排流水线">
        <PipelineSteps mode="feed" />
      </Card>

      <Card
        title={`Feed (${entries.length})`}
        extra={query.data ? <TracePanel traceId={query.data.traceId} requestId={query.data.requestId} raw={query.data} /> : null}
      >
        {query.isError ? (
          <Alert type="error" message={toApiError(query.error).message} showIcon />
        ) : entries.length === 0 ? (
          <Typography.Text type="secondary">暂无 feed(检查推荐/广告是否就绪)。</Typography.Text>
        ) : (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {entries.map((e) => (
              <div
                key={`${e.position}-${e.ad ? 'ad' : 'nat'}-${e.ad ? e.adId : e.itemId}`}
                style={{
                  border: '1px solid #eee',
                  borderRadius: 10,
                  padding: '10px 14px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 14,
                  background: e.ad ? '#fffbe6' : '#fff',
                }}
              >
                <div style={{ width: 28, textAlign: 'center', color: '#bbb', fontWeight: 700 }}>{e.position}</div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <Space size={8} wrap>
                    {e.ad ? <Tag color="gold">赞助</Tag> : null}
                    <Typography.Text strong className="mono">
                      {e.ad ? `ad #${e.adId}` : `#${e.itemId}`}
                    </Typography.Text>
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
