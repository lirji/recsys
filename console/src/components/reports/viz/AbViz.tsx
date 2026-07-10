import { Card, Space, Table, Tag } from 'antd';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import EBar from '../../charts/EBar';
import CliCommandCard from '../CliCommandCard';

// eval/ab-report-*.csv: bucket,impressions,clicks,ctr,ctr_ci_low,ctr_ci_high,users,lift_vs_base,z,p_value,significant,min_sample_per_arm
export default function AbViz({ table }: { table: ReportTable }) {
  const objs = tableToObjects(table);
  const cats = objs.map((o) => o.bucket);
  const ctrPct = objs.map((o) => {
    const v = num(o.ctr);
    return Number.isFinite(v) ? +(v * 100).toFixed(3) : 0;
  });

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="ab-report" note="逐桶 CTR + Wilson 置信区间 + 两比例 z 检验显著性" />
      {objs.length > 0 ? (
        <Card size="small" title="逐桶 CTR (%)">
          <EBar categories={cats} series={[{ name: 'CTR%', data: ctrPct }]} yName="%" height={320} />
        </Card>
      ) : null}
      <Card size="small" title="显著性明细">
        <Table
          size="small"
          rowKey="bucket"
          pagination={false}
          dataSource={objs}
          scroll={{ x: 'max-content' }}
          columns={[
            { title: 'bucket', dataIndex: 'bucket' },
            { title: '曝光', dataIndex: 'impressions' },
            { title: '点击', dataIndex: 'clicks' },
            { title: 'CTR', dataIndex: 'ctr', render: (v: string) => (num(v) * 100).toFixed(3) + '%' },
            {
              title: 'CI',
              key: 'ci',
              render: (_: unknown, o: Record<string, string>) => (
                <span className="mono" style={{ fontSize: 12 }}>
                  [{o.ctr_ci_low}, {o.ctr_ci_high}]
                </span>
              ),
            },
            { title: 'lift', dataIndex: 'lift_vs_base', render: (v: string) => v || '(基线)' },
            { title: 'p', dataIndex: 'p_value' },
            {
              title: '显著',
              dataIndex: 'significant',
              render: (v: string) =>
                v === '' ? <Tag>基线</Tag> : v === 'true' ? <Tag color="green">是</Tag> : <Tag color="default">否</Tag>,
            },
            { title: 'min/臂', dataIndex: 'min_sample_per_arm' },
          ]}
        />
      </Card>
    </Space>
  );
}
