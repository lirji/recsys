import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Empty, Space, Table, Tag, Typography } from 'antd';
import { CloudServerOutlined, SettingOutlined } from '@ant-design/icons';
import { getSystemOps } from '../../api/system';
import { queryKeys } from '../../api/queryKeys';
import { toApiError } from '../../api/client';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { ChartSkeleton } from '../../components/Skeletons';
import { ACCENTS } from '../../theme/tokens';
import type { SystemJobStatus } from '../../api/types';

export default function OpsConsole() {
  const query = useQuery({
    queryKey: queryKeys.systemOps(),
    queryFn: getSystemOps,
    refetchInterval: 15_000,
  });

  if (query.isLoading) return <ChartSkeleton height={280} />;
  if (query.isError) return <Alert type="error" showIcon message={toApiError(query.error).message} />;
  const snap = query.data;
  if (!snap) return null;

  const tuningRows = Object.entries(snap.tuning).map(([field, value]) => ({ field, value }));

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="热配置 / 作业状态"
        accent={ACCENTS.rank}
        description="只读查看 Redis recsys:tuning 覆盖与离线作业 job:status。改配置请走 redis-cli / Nacos,本页不提供写操作。"
      />
      {!snap.redisAvailable ? (
        <Alert type="warning" showIcon message={snap.message || 'Redis 不可用,热配置与作业状态暂空'} />
      ) : null}

      <Card title="recsys:tuning" extra={<SettingOutlined />}>
        {tuningRows.length === 0 ? (
          <EmptyState
            icon={<SettingOutlined />}
            accent={ACCENTS.rank}
            title="暂无热配置覆盖"
            description="空表表示在线全部走 yml 默认。HSET recsys:tuning <field> <value> 即热更。"
          />
        ) : (
          <Table
            size="small"
            rowKey="field"
            pagination={false}
            dataSource={tuningRows}
            columns={[
              { title: 'field', dataIndex: 'field', render: (v: string) => <span className="mono">{v}</span> },
              { title: 'value', dataIndex: 'value', render: (v: string) => <span className="mono">{v}</span> },
            ]}
          />
        )}
      </Card>

      <Card title="离线作业状态" extra={<CloudServerOutlined />}>
        {snap.jobs.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚无 job:status 记录。跑 run-dag 或单个离线作业后会出现。" />
        ) : (
          <Table<SystemJobStatus>
            size="small"
            rowKey="name"
            pagination={false}
            dataSource={snap.jobs}
            scroll={{ x: 720 }}
            columns={[
              { title: '作业', dataIndex: 'name', render: (v: string) => <span className="mono">{v}</span> },
              {
                title: '状态',
                dataIndex: 'status',
                width: 120,
                render: (s: string) => (
                  <Tag color={s === 'ok' || s === 'success' ? 'green' : s === 'failed' || s === 'error' ? 'red' : 'blue'}>
                    {s}
                  </Tag>
                ),
              },
              { title: '更新时间', dataIndex: 'updatedAt', width: 200, render: (v: string | null) => v || '—' },
              {
                title: '详情',
                dataIndex: 'detail',
                render: (v: string | null) => <Typography.Text type="secondary">{v || '—'}</Typography.Text>,
              },
            ]}
          />
        )}
      </Card>
    </Space>
  );
}
