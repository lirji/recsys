import { Space, Table, Tag } from 'antd';
import { BarChartOutlined, TableOutlined } from '@ant-design/icons';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import EBar from '../../charts/EBar';
import EErrorBar from '../../charts/EErrorBar';
import CliCommandCard from '../CliCommandCard';
import CollapsibleCard from '../../CollapsibleCard';
import { ACCENTS } from '../../../theme/tokens';

// eval/ab-report-*.csv: bucket,impressions,clicks,ctr,ctr_ci_low,ctr_ci_high,users,lift_vs_base,z,p_value,significant,min_sample_per_arm
// 旧版报表只有 bucket,impressions,clicks,ctr,users(无 CI/显著性列)—— 检测列在与否,择图降级。
export default function AbViz({ table }: { table: ReportTable }) {
  const objs = tableToObjects(table);
  const hasCi = table.columns.includes('ctr_ci_low') && table.columns.includes('ctr_ci_high');
  const cats = objs.map((o) => o.bucket);
  const pct = (v: string) => {
    const n = num(v);
    return Number.isFinite(n) ? +(n * 100).toFixed(3) : 0;
  };
  const ctrPct = objs.map((o) => pct(o.ctr));

  // significant 空=基线桶;'true'=显著;'false'=不显著。
  const markers = objs.map((o) => {
    const s = o.significant;
    if (s === undefined || s === '') return '基线';
    return s === 'true' ? '★ 显著' : null;
  });

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="ab-report" note="逐桶 CTR + Wilson 置信区间 + 两比例 z 检验显著性" />
      {objs.length > 0 ? (
        <CollapsibleCard
          title={hasCi ? '逐桶 CTR (%) · Wilson 95% CI · ★=vs 基线显著' : '逐桶 CTR (%)'}
          icon={<BarChartOutlined />}
          accent={ACCENTS.rank}
        >
          {hasCi ? (
            <EErrorBar
              categories={cats}
              values={ctrPct}
              low={objs.map((o) => pct(o.ctr_ci_low))}
              high={objs.map((o) => pct(o.ctr_ci_high))}
              markers={markers}
              barName="CTR%"
              yName="%"
              height={340}
            />
          ) : (
            <EBar categories={cats} series={[{ name: 'CTR%', data: ctrPct }]} yName="%" height={320} />
          )}
        </CollapsibleCard>
      ) : null}
      <CollapsibleCard title="显著性明细" icon={<TableOutlined />} accent={ACCENTS.rerank} defaultOpen={false}>
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
                v === undefined || v === '' ? (
                  <Tag>基线</Tag>
                ) : v === 'true' ? (
                  <Tag color="green">是</Tag>
                ) : (
                  <Tag color="default">否</Tag>
                ),
            },
            { title: 'min/臂', dataIndex: 'min_sample_per_arm' },
          ]}
        />
      </CollapsibleCard>
    </Space>
  );
}
