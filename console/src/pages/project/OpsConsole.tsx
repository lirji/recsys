import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Empty, Space, Table, Tag, Typography } from 'antd';
import { CloudServerOutlined, SettingOutlined } from '@ant-design/icons';
import { getSystemOps } from '../../api/system';
import { queryKeys } from '../../api/queryKeys';
import { toApiError } from '../../api/client';
import { formatError } from '../../api/errors';
import { errorCodes, type KnownErrorCode } from '../../api/generated/errorCodes';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import { ChartSkeleton } from '../../components/Skeletons';
import { ACCENTS } from '../../theme/tokens';
import type { SystemJobStatus } from '../../api/types';

// 仅展示已发布状态与目录文案，旧服务返回的自由detail也不可信。
const JOB_STATES: Record<string, { label: string; color: string; fallback?: KnownErrorCode }> = {
  ok: { label: '完成', color: 'green' }, success: { label: '完成', color: 'green' },
  fail: { label: '失败', color: 'red', fallback: 'JOB_EXECUTION_FAILED' },
  failed: { label: '失败', color: 'red', fallback: 'JOB_EXECUTION_FAILED' },
  error: { label: '失败', color: 'red', fallback: 'JOB_EXECUTION_FAILED' },
  skipped: { label: '未执行', color: 'orange', fallback: 'JOB_DEPENDENCY_FAILED' },
  running: { label: '执行中', color: 'blue' },
  UNKNOWN: { label: '未知', color: 'default', fallback: 'JOB_STATUS_INVALID' },
};
const jobState = (state: string) => Object.prototype.hasOwnProperty.call(JOB_STATES, state)
  ? JOB_STATES[state] : JOB_STATES.UNKNOWN;
// 旧服务可能把损坏记录的任意尾部当时间，展示前也须校验。
function jobTime(value: string | null): string {
  if (!value || !/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value)) return '—';
  const normalized = value.replace(' ', 'T');
  const time = new Date(`${normalized}Z`);
  return Number.isFinite(time.getTime()) && time.toISOString().slice(0, 19) === normalized ? value : '—';
}

function jobDetail(row: SystemJobStatus): string {
  const code = row.code;
  if (code?.startsWith('JOB_') && Object.prototype.hasOwnProperty.call(errorCodes, code)) return formatError(code as KnownErrorCode);
  const fallback = jobState(row.status).fallback;
  return fallback ? formatError(fallback) : '—';
}

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
        <Alert type="warning" showIcon message={formatError('CONSOLE_OPS_UNAVAILABLE')} />
      ) : null}

      <Card title="recsys:tuning" extra={<SettingOutlined />}>
        {!snap.redisAvailable ? <Typography.Text>运行配置未知</Typography.Text> : tuningRows.length === 0 ? (
          <EmptyState
            icon={<SettingOutlined />}
            accent={ACCENTS.rank}
            title="暂无热配置覆盖"
            description="未读取到 Redis 热配置覆盖；实际运行配置请结合服务配置核对。"
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
        {!snap.redisAvailable ? <Typography.Text>作业状态未知</Typography.Text> : snap.jobs.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚无 job:status 记录。通过 run-dag 执行后会出现。" />
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
                  <Tag color={jobState(s).color}>
                    {jobState(s).label}
                  </Tag>
                ),
              },
              { title: '更新时间', dataIndex: 'updatedAt', width: 200, render: jobTime },
              {
                title: '详情',
                dataIndex: 'detail',
                render: (_: string | null, row: SystemJobStatus) => <Typography.Text type="secondary">{jobDetail(row)}</Typography.Text>,
              },
            ]}
          />
        )}
      </Card>
    </Space>
  );
}
