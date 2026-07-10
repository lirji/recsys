import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Input, InputNumber, Space, Spin, Typography } from 'antd';
import { getRecommend } from '../../api/recommend';
import { makeEvent, reportBehavior } from '../../api/behavior';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import ItemCard from '../../components/explain/ItemCard';
import PipelineSteps from '../../components/explain/PipelineSteps';
import TracePanel from '../../components/explain/TracePanel';

export default function RecommendConsole() {
  const { userId, scene } = useGlobalUser();
  const { message } = App.useApp();
  const [size, setSize] = useState(10);
  const [q, setQ] = useState('');

  const query = useQuery({
    queryKey: ['recommend'],
    queryFn: () => getRecommend({ userId, size, scene, q }),
    enabled: false,
  });

  // 进入页面自动拉一次(用当前全局 userId)。
  useEffect(() => {
    query.refetch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const items = query.data?.items ?? [];
  const maxScore = items.reduce((m, it) => Math.max(m, it.score), 0);

  const report = async (itemId: number, action: 'IMPRESSION' | 'CLICK') => {
    try {
      await reportBehavior(makeEvent(userId, itemId, action, scene));
      message.success(`已上报 ${action} · item ${itemId}`);
    } catch (e) {
      message.error('上报失败: ' + toApiError(e).message);
    }
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" bordered={false}>
        <Space wrap>
          <span>size</span>
          <InputNumber min={1} max={200} value={size} onChange={(v) => v && setSize(v)} />
          <span>q(可选,带 q 走 query 驱动)</span>
          <Input
            allowClear
            placeholder="留空=纯个性化推荐"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ width: 240 }}
            onPressEnter={() => query.refetch()}
          />
          <Button type="primary" loading={query.isFetching} onClick={() => query.refetch()}>
            获取推荐
          </Button>
          <Typography.Text type="secondary">
            userId={userId} · scene={scene}
          </Typography.Text>
        </Space>
      </Card>

      <Card size="small" title="处理流水线">
        <PipelineSteps mode="rec" />
      </Card>

      <Card
        title={`推荐结果 (${items.length})`}
        extra={query.data ? <TracePanel traceId={query.data.traceId} raw={query.data} /> : null}
      >
        {query.isError ? (
          <Alert type="error" message={toApiError(query.error).message} showIcon />
        ) : query.isFetching && !query.data ? (
          <Spin />
        ) : items.length === 0 ? (
          <Typography.Text type="secondary">暂无结果(检查是否已灌数据/向量,或 rec-engine 是否启动)。</Typography.Text>
        ) : (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {items.map((it, i) => (
              <ItemCard
                key={it.itemId}
                item={it}
                rank={i + 1}
                maxScore={maxScore}
                actions={
                  <>
                    <Button size="small" onClick={() => report(it.itemId, 'IMPRESSION')}>
                      曝光
                    </Button>
                    <Button size="small" type="primary" ghost onClick={() => report(it.itemId, 'CLICK')}>
                      👍 点击
                    </Button>
                  </>
                }
              />
            ))}
          </Space>
        )}
      </Card>
    </Space>
  );
}
