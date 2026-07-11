import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Descriptions, Input, Space, Table, Tag, Typography } from 'antd';
import { parseQuery } from '../../api/query';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import FunnelBand from '../../components/funnel/FunnelBand';
import PageHeader from '../../components/PageHeader';
import { deriveQueryStages } from '../../components/funnel/derive';
import { ACCENTS, STATUS } from '../../theme/tokens';
import TracePanel from '../../components/explain/TracePanel';

export default function QueryParseConsole() {
  const { userId } = useGlobalUser();
  const [q, setQ] = useState('action comedy');

  const query = useQuery({
    queryKey: ['query-parse'],
    queryFn: () => parseQuery({ q, userId }),
    enabled: false,
  });
  const sq = query.data;
  const embDim = sq?.embedding?.length ?? 0;
  const stages = useMemo(() => deriveQueryStages(sq), [sq]);
  const flowing = !!sq && !query.isFetching;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="Query 理解"
        accent={ACCENTS.gsp}
        description="分词 / 意图 / 改写 / 向量化 —— 搜索与搜索广告的共同入口。"
      />
      <Card size="small" bordered={false}>
        <Space wrap>
          <span>q</span>
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ width: 320 }}
            onPressEnter={() => q.trim() && query.refetch()}
          />
          <Button type="primary" loading={query.isFetching} onClick={() => q.trim() && query.refetch()}>
            解析
          </Button>
          <Typography.Text type="secondary">userId={userId}</Typography.Text>
        </Space>
      </Card>

      <FunnelBand
        dense
        stages={stages}
        flowing={flowing}
        status={flowing ? { color: STATUS.online, label: '在线', pulse: true } : undefined}
      />

      {query.isError ? <Alert type="error" message={toApiError(query.error).message} showIcon /> : null}

      {sq ? (
        <Card title="Query 理解结果" extra={<TracePanel raw={sq} />}>
          <Descriptions bordered size="small" column={1} style={{ marginBottom: 16 }}>
            <Descriptions.Item label="raw">{sq.raw}</Descriptions.Item>
            <Descriptions.Item label="normalized">{sq.normalized}</Descriptions.Item>
            <Descriptions.Item label="rewrites">
              {sq.rewrites?.length ? sq.rewrites.map((r) => <Tag key={r}>{r}</Tag>) : <Typography.Text type="secondary">无</Typography.Text>}
            </Descriptions.Item>
            <Descriptions.Item label="embedding">
              {embDim > 0 ? (
                <span className="mono">{embDim} 维 · 前几维 [{sq.embedding!.slice(0, 5).map((x) => x.toFixed(3)).join(', ')} …]</span>
              ) : (
                <Typography.Text type="secondary">无(LLM/BGE 未就绪 → 词法兜底)</Typography.Text>
              )}
            </Descriptions.Item>
          </Descriptions>

          <Space align="start" size={24} wrap style={{ width: '100%' }}>
            <div style={{ minWidth: 280 }}>
              <Typography.Title level={5}>词项权重 terms</Typography.Title>
              <Table
                size="small"
                rowKey="term"
                pagination={false}
                dataSource={sq.terms ?? []}
                columns={[
                  { title: 'term', dataIndex: 'term' },
                  { title: 'weight', dataIndex: 'weight', render: (w: number) => <span className="mono">{w.toFixed(4)}</span> },
                ]}
              />
            </div>
            <div style={{ minWidth: 280 }}>
              <Typography.Title level={5}>意图类目 intents</Typography.Title>
              <Table
                size="small"
                rowKey="category"
                pagination={false}
                dataSource={sq.intents ?? []}
                columns={[
                  { title: 'category', dataIndex: 'category' },
                  { title: 'score', dataIndex: 'score', render: (s: number) => <span className="mono">{s.toFixed(4)}</span> },
                ]}
              />
            </div>
          </Space>
        </Card>
      ) : null}
    </Space>
  );
}
