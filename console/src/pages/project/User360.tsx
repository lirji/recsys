import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Badge, Button, Card, Descriptions, Empty, InputNumber, Space, Table, Tag, Typography } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { getUserProfile } from '../../api/user360';
import { getInterests } from '../../api/user';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import { useItemMeta } from '../../hooks/useItemMeta';
import PageHeader from '../../components/PageHeader';
import StatCard from '../../components/StatCard';
import { ChartSkeleton } from '../../components/Skeletons';
import { ACCENTS } from '../../theme/tokens';
import type { UserBehaviorRow } from '../../api/types';

const fmtTs = (ms: number) => (ms ? new Date(ms).toLocaleString() : '—');

export default function User360() {
  const { userId } = useGlobalUser();
  const [uid, setUid] = useState(userId);
  const [applied, setApplied] = useState(userId);

  const query = useQuery({
    queryKey: ['user360', applied],
    queryFn: () => getUserProfile(applied),
  });
  const interests = useQuery({
    queryKey: ['user360-interests', applied],
    queryFn: () => getInterests(applied),
    retry: false,
  });

  const view = query.data;
  const behaviorIds = view?.recentBehavior.map((b) => b.itemId) ?? [];
  const seqIds = view?.realtime.recentSeqItems ?? [];
  const itemMeta = useItemMeta([...behaviorIds, ...seqIds]);

  const columns = useMemo(
    () => [
      {
        title: '物品',
        dataIndex: 'itemId',
        render: (id: number) => {
          const m = itemMeta(id);
          return m?.title ? (
            <span>
              <Typography.Text strong>{m.title}</Typography.Text>{' '}
              <Typography.Text type="secondary" className="mono" style={{ fontSize: 12 }}>
                #{id}
              </Typography.Text>
            </span>
          ) : (
            <span className="mono">#{id}</span>
          );
        },
      },
      { title: '行为', dataIndex: 'action', render: (a: string) => <Tag>{a}</Tag> },
      { title: '场景', dataIndex: 'scene', render: (s: string | null) => s || '—' },
      { title: '分桶', dataIndex: 'bucket', render: (b: string | null) => (b ? <Tag color="geekblue">{b}</Tag> : '—') },
      { title: '位次', dataIndex: 'position', render: (p: number | null) => (p == null ? '—' : p) },
      { title: '评分', dataIndex: 'value', render: (v: number | null) => (v == null ? '—' : v) },
      { title: '时间', dataIndex: 'ts', render: (ts: number) => fmtTs(ts) },
    ],
    // itemMeta 依赖每次渲染新函数,故不进 deps(表格渲染即用当前 meta);列结构稳定。
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [query.dataUpdatedAt],
  );

  const run = () => setApplied(uid);

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="用户 360"
        accent={ACCENTS.rank}
        description="单用户全景:静态画像 + 行为聚合 + 向量存在性 + 在线实时态(Redis)。只读聚合,DB/Redis 挂则对应部分降级。"
      />
      <Card size="small" bordered={false}>
        <Space wrap>
          <span>userId</span>
          <InputNumber min={1} value={uid} onChange={(v) => v && setUid(v)} onPressEnter={run} />
          <Button type="primary" icon={<UserOutlined />} loading={query.isFetching} onClick={run}>
            查询
          </Button>
        </Space>
      </Card>

      {query.isError ? (
        <Alert type="error" showIcon message={toApiError(query.error).message} />
      ) : query.isFetching && !view ? (
        <ChartSkeleton height={320} />
      ) : !view ? null : !view.exists ? (
        <Card>
          <Empty description={`用户 ${applied} 无画像与行为记录(未知用户)`} />
        </Card>
      ) : (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(180px,1fr))', gap: 12 }}>
            <StatCard title="交互总数" value={view.stats.totalInteractions} accent={ACCENTS.recall} />
            <StatCard title="交互类目数" value={view.stats.distinctCategories} accent={ACCENTS.rank} />
            <StatCard title="已看物品(seen)" value={view.realtime.seenCount} accent={ACCENTS.rerank} />
            <StatCard
              title="用户向量"
              value={view.hasEmbedding ? '已就绪' : '缺失'}
              valueColor={view.hasEmbedding ? ACCENTS.gsp : ACCENTS.warn}
              accent={ACCENTS.gsp}
            />
          </div>

          <Card title="画像与实时态" size="small">
            <Descriptions column={{ xs: 1, sm: 2 }} size="small" bordered>
              <Descriptions.Item label="画像更新时间">{fmtTs(view.profileUpdatedAt ?? 0)}</Descriptions.Item>
              <Descriptions.Item label="结果缓存(cache:rec)">
                {view.realtime.recCached ? <Tag color="green">已缓存</Tag> : <Tag>无</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="A/B 分桶">
                {view.stats.bucketsSeen.length
                  ? view.stats.bucketsSeen.map((b) => <Tag key={b} color="geekblue">{b}</Tag>)
                  : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="行为分布">
                {Object.entries(view.stats.actionCounts).map(([a, c]) => (
                  <Tag key={a}>{a}: {c}</Tag>
                ))}
              </Descriptions.Item>
              <Descriptions.Item label="实时类目偏好(rt:user)" span={2}>
                <Badge
                  status={view.realtime.available ? 'success' : 'default'}
                  text={view.realtime.available ? 'Redis 在线' : 'Redis 不可达(降级)'}
                />
                <div style={{ marginTop: 6 }}>
                  {Object.keys(view.realtime.rtCategoryPrefs).length
                    ? Object.entries(view.realtime.rtCategoryPrefs)
                        .sort((a, b) => b[1] - a[1])
                        .map(([cat, cnt]) => (
                          <Tag key={cat} color="cyan">{cat}: {cnt}</Tag>
                        ))
                    : <Typography.Text type="secondary">无实时偏好</Typography.Text>}
                </div>
              </Descriptions.Item>
              <Descriptions.Item label="兴趣画像(rec-engine)" span={2}>
                {interests.isError ? (
                  <Typography.Text type="secondary">兴趣接口不可用</Typography.Text>
                ) : interests.data?.categories?.length ? (
                  interests.data.categories.map((c) => <Tag key={c} color="purple">{c}</Tag>)
                ) : (
                  <Typography.Text type="secondary">无</Typography.Text>
                )}
              </Descriptions.Item>
            </Descriptions>
          </Card>

          {view.profileJson ? (
            <Card title="app_user.profile" size="small">
              <Typography.Paragraph>
                <pre className="mono" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                  {prettyJson(view.profileJson)}
                </pre>
              </Typography.Paragraph>
            </Card>
          ) : null}

          <Card title={`近期行为(${view.recentBehavior.length})`} size="small">
            <Table<UserBehaviorRow>
              size="small"
              rowKey={(r, i) => `${r.itemId}-${r.ts}-${i}`}
              dataSource={view.recentBehavior}
              columns={columns}
              pagination={{ pageSize: 10, size: 'small' }}
            />
          </Card>
        </>
      )}
    </Space>
  );
}

function prettyJson(s: string): string {
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}
