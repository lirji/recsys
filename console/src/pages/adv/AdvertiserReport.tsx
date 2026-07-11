import { useQuery } from '@tanstack/react-query';
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

export default function AdvertiserReport() {
  const { id } = useParams();
  const advertiserId = Number(id);
  const query = useQuery({ queryKey: ['advertiser-report', advertiserId], queryFn: () => advertiserReport(advertiserId) });
  const rows: AdReportRow[] = query.data ?? [];

  const labels = rows.map((r) => r.title || `#${r.adId}`);

  const columns = [
    { title: 'adId', dataIndex: 'adId', width: 80 },
    { title: '标题', dataIndex: 'title', ellipsis: true },
    { title: '曝光', dataIndex: 'impressions', width: 90 },
    { title: '点击', dataIndex: 'clicks', width: 80 },
    { title: '转化', dataIndex: 'conversions', width: 80 },
    { title: '花费', dataIndex: 'spend', width: 100, render: (v: number) => v?.toFixed(2) },
    { title: 'CTR', dataIndex: 'ctr', width: 90, render: (v: number) => (v * 100).toFixed(2) + '%' },
    { title: 'CVR', dataIndex: 'cvr', width: 90, render: (v: number) => (v * 100).toFixed(2) + '%' },
    { title: 'eCPM', dataIndex: 'ecpm', width: 100, render: (v: number) => v?.toFixed(2) },
  ];

  if (query.isLoading)
    return (
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <ChartSkeleton height={220} />
        <ChartSkeleton height={300} />
      </Space>
    );
  if (query.isError) return <Alert type="error" showIcon message={toApiError(query.error).message} />;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card title={`广告主 #${advertiserId} 投放报表`} style={{ borderLeft: `3px solid ${ACCENTS.gsp}` }}>
        <Table<AdReportRow> rowKey="adId" size="small" columns={columns} dataSource={rows} pagination={false} />
      </Card>
      {rows.length > 0 ? (
        <>
          <CollapsibleCard title="曝光 / 点击 / 转化" icon={<BarChartOutlined />} accent={ACCENTS.recall}>
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
