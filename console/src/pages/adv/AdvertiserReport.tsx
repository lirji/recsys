import { useEffect, useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Card, Space, Table } from 'antd';
import { BarChartOutlined, LineChartOutlined } from '@ant-design/icons';
import { ChartSkeleton } from '../../components/Skeletons';
import { useParams } from 'react-router-dom';
import { advertiserReport } from '../../api/advertiser';
import { toApiError } from '../../api/client';
import type { AdReportRow } from '../../api/types';
import EBar from '../../components/charts/EBar';
import CollapsibleCard from '../../components/CollapsibleCard';
import { ACCENTS } from '../../theme/tokens';
import PageHeader from '../../components/PageHeader';
import SandboxBillingReportPanel from '../../components/adv/SandboxBillingReportPanel';
import { useAdvertiserReportIdentity } from '../../hooks/useAdvertiserReportIdentity';
import { SANDBOX_RECONCILIATION_ENABLED } from '../../config/sandboxActions';
import type { ExposureIdentity } from '../../api/exposure';

export default function AdvertiserReport() {
  const { id } = useParams();
  const advertiserId = id ?? '';
  const identity = useAdvertiserReportIdentity();
  return (
    <Space direction="vertical" size={16} style={{ width: '100%', minWidth: 0 }}>
      <PageHeader
        title="广告主投放报表"
        description={
          <span style={{ overflowWrap: 'anywhere' }}>
            广告主 #{advertiserId} · 账户日资金与历史事件统计分别展示
          </span>
        }
        accent={ACCENTS.gsp}
      />
      {SANDBOX_RECONCILIATION_ENABLED && (
        <SandboxBillingReportPanel
          key={advertiserId}
          advertiserId={advertiserId}
          identity={identity}
        />
      )}
      <LegacyAdvertiserReport advertiserId={advertiserId} identity={identity} />
    </Space>
  );
}

/** 旧事件统计独立加载；身份变化不能让它继续展示上个主体的缓存。 */
function LegacyAdvertiserReport({
  advertiserId,
  identity,
}: {
  advertiserId: string;
  identity: ExposureIdentity | null;
}) {
  const client = useQueryClient();
  const queryKey = useMemo(
    () => ['advertiser-report', identity?.scope, identity?.epoch, advertiserId],
    [identity?.scope, identity?.epoch, advertiserId],
  );
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => advertiserReport(advertiserId, identity!, signal),
    enabled: !!identity,
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  useEffect(() => {
    if (query.isError && [401, 403].includes(toApiError(query.error).status ?? 0))
      client.removeQueries({ queryKey, exact: true });
  }, [query.isError, query.error, queryKey, client]);
  const rows: AdReportRow[] = query.data ?? [];

  const labels = rows.map((r) => r.title || `#${r.adId}`);

  const columns = [
    { title: 'adId', dataIndex: 'adId', width: 80 },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '曝光', dataIndex: 'impressions', width: 90 },
    { title: '点击', dataIndex: 'clicks', width: 80 },
    { title: '转化', dataIndex: 'conversions', width: 80 },
    { title: '花费', dataIndex: 'spend', width: 100, render: (v: number) => v?.toFixed(2) },
    {
      title: 'CTR',
      dataIndex: 'ctr',
      width: 90,
      render: (v: number) => (v * 100).toFixed(2) + '%',
    },
    {
      title: 'CVR',
      dataIndex: 'cvr',
      width: 90,
      render: (v: number) => (v * 100).toFixed(2) + '%',
    },
    { title: 'eCPM', dataIndex: 'ecpm', width: 100, render: (v: number) => v?.toFixed(2) },
  ];

  if (!identity || query.isPending || query.isFetching)
    return (
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <ChartSkeleton height={220} />
        <ChartSkeleton height={300} />
      </Space>
    );
  if (query.isError)
    return <Alert type="error" showIcon message={toApiError(query.error).message} />;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="历史事件统计口径"
        description="来源为全期 ad_event 聚合，花费沿用旧事件估计口径；没有提供全局消息水位。账户日资金请以单独的 Sandbox 快照为准。"
      />
      <Card title="历史事件统计（全期）" style={{ borderLeft: `3px solid ${ACCENTS.gsp}` }}>
        <Table<AdReportRow>
          rowKey="adId"
          size="small"
          columns={columns}
          dataSource={rows}
          pagination={false}
          scroll={{ x: 850 }}
        />
      </Card>
      {rows.length > 0 ? (
        <>
          <CollapsibleCard
            title="曝光 / 点击 / 转化"
            icon={<BarChartOutlined />}
            accent={ACCENTS.recall}
          >
            <EBar
              categories={labels}
              series={[
                { name: '曝光', data: rows.map((r) => r.impressions) },
                { name: '点击', data: rows.map((r) => r.clicks) },
                { name: '转化', data: rows.map((r) => r.conversions) },
              ]}
            />
          </CollapsibleCard>
          <CollapsibleCard title="CTR / CVR (%)" icon={<LineChartOutlined />} accent={ACCENTS.rank}>
            <EBar
              categories={labels}
              yName="%"
              series={[
                { name: 'CTR%', data: rows.map((r) => +(r.ctr * 100).toFixed(3)) },
                { name: 'CVR%', data: rows.map((r) => +(r.cvr * 100).toFixed(3)) },
              ]}
            />
          </CollapsibleCard>
        </>
      ) : null}
    </Space>
  );
}
