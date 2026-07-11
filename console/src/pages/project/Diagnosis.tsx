import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Space, Table, Tag, Typography } from 'antd';
import { CheckCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { getDiagnosis } from '../../api/diagnosis';
import { toApiError } from '../../api/client';
import PageHeader from '../../components/PageHeader';
import { ChartSkeleton } from '../../components/Skeletons';
import { ACCENTS } from '../../theme/tokens';
import type { DiagnosisCheck } from '../../api/types';

// 状态 → AntD Tag 颜色。PASS/UP=绿、WARN/UNKNOWN=橙、FAIL/ERROR/DOWN=红、INFO=蓝。
function statusColor(s: string): string {
  const u = s.toUpperCase();
  if (u === 'PASS' || u === 'UP') return 'success';
  if (u === 'FAIL' || u === 'ERROR' || u === 'DOWN') return 'error';
  if (u === 'WARN' || u === 'UNKNOWN') return 'warning';
  return 'blue';
}

const fmtTs = (ms: number) => (ms ? new Date(ms).toLocaleString() : '—');

export default function Diagnosis() {
  const query = useQuery({
    queryKey: ['diagnosis'],
    queryFn: getDiagnosis,
  });
  const report = query.data;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="一键诊断"
        accent={ACCENTS.gsp}
        description="复用服务健康 + 数据质量 + 离线评估 + 链路延迟等既有信号,组装一张系统体检清单。"
        extra={
          <Button icon={<ReloadOutlined />} loading={query.isFetching} onClick={() => query.refetch()}>
            重新诊断
          </Button>
        }
      />
      {query.isError ? (
        <Alert type="error" showIcon message={toApiError(query.error).message} />
      ) : query.isFetching && !report ? (
        <ChartSkeleton height={320} />
      ) : !report ? null : (
        <>
          <Card size="small">
            <Space size={12} align="center">
              <CheckCircleOutlined style={{ fontSize: 22, color: colorHex(report.overall) }} />
              <Typography.Text style={{ fontSize: 16 }}>总判定</Typography.Text>
              <Tag color={statusColor(report.overall)} style={{ fontSize: 14, padding: '2px 12px' }}>
                {report.overall}
              </Tag>
              <Typography.Text type="secondary">诊断时刻 {fmtTs(report.checkedAt)}</Typography.Text>
            </Space>
          </Card>
          <Card title={`检查项(${report.checks.length})`} size="small">
            <Table<DiagnosisCheck>
              size="small"
              rowKey="key"
              pagination={false}
              dataSource={report.checks}
              columns={[
                { title: '检查', dataIndex: 'name', width: 220 },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 100,
                  render: (s: string) => <Tag color={statusColor(s)}>{s}</Tag>,
                },
                { title: '明细', dataIndex: 'detail' },
              ]}
            />
          </Card>
        </>
      )}
    </Space>
  );
}

function colorHex(s: string): string {
  const u = s.toUpperCase();
  if (u === 'PASS') return ACCENTS.gsp;
  if (u === 'FAIL') return ACCENTS.warn;
  return '#faad14';
}
