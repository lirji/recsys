import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Input, InputNumber, Space, Spin, Typography } from 'antd';
import { getSearch } from '../../api/recommend';
import { makeEvent, reportBehavior } from '../../api/behavior';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import ItemCard from '../../components/explain/ItemCard';
import PipelineSteps from '../../components/explain/PipelineSteps';
import TracePanel from '../../components/explain/TracePanel';

export default function SearchConsole() {
  const { userId } = useGlobalUser();
  const { message } = App.useApp();
  const [size, setSize] = useState(10);
  const [q, setQ] = useState('action');

  const query = useQuery({
    queryKey: ['search'],
    queryFn: () => getSearch({ q, userId, size }),
    enabled: false,
  });

  const items = query.data?.items ?? [];
  const maxScore = items.reduce((m, it) => Math.max(m, it.score), 0);

  const run = () => {
    if (!q.trim()) {
      message.warning('请输入搜索词 q');
      return;
    }
    query.refetch();
  };

  const report = async (itemId: number) => {
    try {
      await reportBehavior(makeEvent(userId, itemId, 'CLICK', 'search'));
      message.success(`已上报点击 · item ${itemId}`);
    } catch (e) {
      message.error('上报失败: ' + toApiError(e).message);
    }
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" bordered={false}>
        <Space wrap>
          <span>q</span>
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ width: 260 }}
            placeholder="搜索词"
            onPressEnter={run}
          />
          <span>size</span>
          <InputNumber min={1} max={200} value={size} onChange={(v) => v && setSize(v)} />
          <Button type="primary" loading={query.isFetching} onClick={run}>
            搜索
          </Button>
          <Typography.Text type="secondary">userId={userId}(query 驱动:相关性主导,冷用户也走此链路)</Typography.Text>
        </Space>
      </Card>

      <Card size="small" title="处理流水线">
        <PipelineSteps mode="search" />
      </Card>

      <Card
        title={`搜索结果 (${items.length})`}
        extra={query.data ? <TracePanel traceId={query.data.traceId} raw={query.data} /> : null}
      >
        {query.isError ? (
          <Alert type="error" message={toApiError(query.error).message} showIcon />
        ) : query.isFetching && !query.data ? (
          <Spin />
        ) : items.length === 0 ? (
          <Typography.Text type="secondary">暂无结果。</Typography.Text>
        ) : (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {items.map((it, i) => (
              <ItemCard
                key={it.itemId}
                item={it}
                rank={i + 1}
                maxScore={maxScore}
                actions={
                  <Button size="small" type="primary" ghost onClick={() => report(it.itemId)}>
                    👍 点击
                  </Button>
                }
              />
            ))}
          </Space>
        )}
      </Card>
    </Space>
  );
}
