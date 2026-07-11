import { useQuery } from '@tanstack/react-query';
import { Alert, Badge, Card, Empty, List, Space, Tag, Typography } from 'antd';
import { getAlerts } from '../../api/alerts';
import { toApiError } from '../../api/client';
import PageHeader from '../../components/PageHeader';
import { ChartSkeleton } from '../../components/Skeletons';
import { ACCENTS } from '../../theme/tokens';
import type { AlertItem } from '../../api/types';

function levelColor(level: string): string {
  const u = level.toUpperCase();
  if (u === 'ERROR') return 'error';
  if (u === 'WARN') return 'warning';
  return 'blue';
}

const fmtTs = (ms: number) => (ms ? new Date(ms).toLocaleString() : '—');

export default function Alerts() {
  const query = useQuery({
    queryKey: ['alerts'],
    queryFn: getAlerts,
    refetchInterval: 30_000, // 每 30s 刷新一次当前告警
  });
  const alerts = query.data ?? [];
  const errors = alerts.filter((a) => a.level.toUpperCase() === 'ERROR').length;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="告警面板"
        accent={ACCENTS.warn}
        description="从服务健康 / 数据质量报表 / 链路延迟派生的当前告警(ERROR→WARN→INFO)。每 30s 自动刷新。"
        extra={
          <Badge
            count={errors}
            showZero
            color={errors ? '#ff6b72' : '#2ee6a6'}
            overflowCount={99}
            title="ERROR 数"
          />
        }
      />
      {query.isError ? (
        <Alert type="error" showIcon message={toApiError(query.error).message} />
      ) : query.isFetching && !query.data ? (
        <ChartSkeleton height={280} />
      ) : alerts.length === 0 ? (
        <Card>
          <Empty description="当前无告警 · 系统健康" />
        </Card>
      ) : (
        <Card size="small">
          <List<AlertItem>
            dataSource={alerts}
            renderItem={(a) => (
              <List.Item>
                <Space size={10} wrap>
                  <Tag color={levelColor(a.level)}>{a.level}</Tag>
                  <Tag>{a.source}</Tag>
                  <Typography.Text>{a.message}</Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {fmtTs(a.ts)}
                  </Typography.Text>
                </Space>
              </List.Item>
            )}
          />
        </Card>
      )}
    </Space>
  );
}
