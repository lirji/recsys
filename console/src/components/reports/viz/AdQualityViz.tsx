import { Card, Space } from 'antd';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import EBar from '../../charts/EBar';
import CsvTable from '../CsvTable';
import CliCommandCard from '../CliCommandCard';

// eval/ad-quality-*.csv: ad_id,impr,clicks,conv,rel,ctr_shrunk,cvr_shrunk,relN,ctrN,cvrN,quality
export default function AdQualityViz({ table }: { table: ReportTable }) {
  const objs = tableToObjects(table).slice(0, 30);
  const cats = objs.map((o) => `#${o.ad_id}`);
  const n = (o: Record<string, string>, k: string) => {
    const v = num(o[k]);
    return Number.isFinite(v) ? +v.toFixed(4) : 0;
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="ad-quality" note="相关性/CTR/CVR 三因子贝叶斯收缩融合成质量度乘子(围绕 1.0)" />
      {objs.length > 0 ? (
        <Card size="small" title="质量度因子(前 30 条)">
          <EBar
            categories={cats}
            series={[
              { name: 'quality', data: objs.map((o) => n(o, 'quality')) },
              { name: 'relN', data: objs.map((o) => n(o, 'relN')) },
              { name: 'ctrN', data: objs.map((o) => n(o, 'ctrN')) },
              { name: 'cvrN', data: objs.map((o) => n(o, 'cvrN')) },
            ]}
            height={360}
          />
        </Card>
      ) : null}
      <Card size="small" title="明细">
        <CsvTable table={table} />
      </Card>
    </Space>
  );
}
