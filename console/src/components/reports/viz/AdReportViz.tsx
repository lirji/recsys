import { Card, Space } from 'antd';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import EBar from '../../charts/EBar';
import CsvTable from '../CsvTable';
import CliCommandCard from '../CliCommandCard';

// eval/ad-report-*.csv: position,impressions,clicks,conversions,revenue,ctr,cvr,ecpm,avg_relevance
// 同文件混有 position 数字行 + ALL 汇总行 + bucket:* 分桶行 —— 图只取数字 position 行。
export default function AdReportViz({ table }: { table: ReportTable }) {
  const objs = tableToObjects(table);
  const posRows = objs.filter((o) => /^\d+$/.test((o.position ?? '').trim()));
  const cats = posRows.map((o) => `pos ${o.position}`);
  const n = (o: Record<string, string>, k: string) => {
    const v = num(o[k]);
    return Number.isFinite(v) ? v : 0;
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="ad-report" note="按广告位次聚合曝光/点击/转化/收入/eCPM" />
      {posRows.length > 0 ? (
        <>
          <Card size="small" title="逐位次:曝光 / 点击 / 转化">
            <EBar
              categories={cats}
              series={[
                { name: '曝光', data: posRows.map((o) => n(o, 'impressions')) },
                { name: '点击', data: posRows.map((o) => n(o, 'clicks')) },
                { name: '转化', data: posRows.map((o) => n(o, 'conversions')) },
              ]}
            />
          </Card>
          <Card size="small" title="逐位次:eCPM / 收入">
            <EBar
              categories={cats}
              series={[
                { name: 'eCPM', data: posRows.map((o) => +n(o, 'ecpm').toFixed(4)) },
                { name: '收入', data: posRows.map((o) => +n(o, 'revenue').toFixed(4)) },
              ]}
            />
          </Card>
        </>
      ) : null}
      <Card size="small" title="明细(含 ALL 汇总 / bucket 分桶行)">
        <CsvTable table={table} />
      </Card>
    </Space>
  );
}
