import { Space, Table } from 'antd';
import { BarChartOutlined, TableOutlined } from '@ant-design/icons';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import EBar from '../../charts/EBar';
import CliCommandCard from '../CliCommandCard';
import CollapsibleCard from '../../CollapsibleCard';
import CsvTable from '../CsvTable';
import { ACCENTS } from '../../../theme/tokens';

/** bandit-*.csv:臂/类目级探索统计。列不固定,按常见度量画柱,其余落明细表。 */
export default function BanditViz({ table }: { table: ReportTable }) {
  const objs = tableToObjects(table);
  const labelCol =
    ['arm', 'category', 'item_id', 'variant', 'bucket', 'name'].find((c) => table.columns.includes(c)) ?? table.columns[0];
  const metricCols = table.columns.filter((c) =>
    /impr|click|reward|ctr|n$|count|ucb|mean/i.test(c),
  );
  const cats = objs.map((o) => o[labelCol] || '—');

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="bandit-stats" note="contextual bandit 充分统计 / 冷启动类目 UCB,供在线探索加成" />
      {metricCols.length > 0 && objs.length > 0 ? (
        <CollapsibleCard title="探索统计" icon={<BarChartOutlined />} accent={ACCENTS.gsp}>
          <EBar
            categories={cats}
            series={metricCols.slice(0, 3).map((col) => ({
              name: col,
              data: objs.map((o) => {
                const n = num(o[col]);
                return Number.isFinite(n) ? n : 0;
              }),
            }))}
            height={320}
          />
        </CollapsibleCard>
      ) : null}
      <CollapsibleCard title="明细" icon={<TableOutlined />} accent={ACCENTS.rank} defaultOpen={metricCols.length === 0}>
        {objs.length > 0 ? (
          <Table
            size="small"
            rowKey={(_, i) => String(i)}
            pagination={false}
            dataSource={objs}
            scroll={{ x: 'max-content' }}
            columns={table.columns.map((c) => ({ title: c, dataIndex: c }))}
          />
        ) : (
          <CsvTable table={table} />
        )}
      </CollapsibleCard>
    </Space>
  );
}
