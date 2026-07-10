import { Card, Space } from 'antd';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import EBar from '../../charts/EBar';
import CsvTable from '../CsvTable';
import CliCommandCard from '../CliCommandCard';

const safe = (v: string) => {
  const n = num(v);
  return Number.isFinite(n) ? +n.toFixed(4) : 0;
};

// eval/metrics-*.csv: variant,k,users,precision,recall,ndcg,map,mrr,hitrate,coverage,diversity,novelty
export default function EvalViz({ table }: { table: ReportTable }) {
  const objs = tableToObjects(table);
  const cats = objs.map((o) => `${o.variant}@${o.k}`);
  const metrics = ['ndcg', 'precision', 'recall', 'hitrate'];
  const series = metrics.map((m) => ({ name: m, data: objs.map((o) => safe(o[m])) }));

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="eval" note="离线推荐质量:按时间切分留出集,复用 recall→rank" />
      {objs.length > 0 ? (
        <Card size="small" title="准确性指标 (NDCG / Precision / Recall / HitRate)">
          <EBar categories={cats} series={series} height={360} />
        </Card>
      ) : null}
      <Card size="small" title="明细">
        <CsvTable table={table} />
      </Card>
    </Space>
  );
}
