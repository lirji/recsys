import { useMemo, useState } from 'react';
import { Select, Space, Typography } from 'antd';
import { BarChartOutlined, RadarChartOutlined, TableOutlined } from '@ant-design/icons';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import EBar from '../../charts/EBar';
import ERadar from '../../charts/ERadar';
import CsvTable from '../CsvTable';
import CliCommandCard from '../CliCommandCard';
import CollapsibleCard from '../../CollapsibleCard';
import { ACCENTS } from '../../../theme/tokens';

const safe = (v: string) => {
  const n = num(v);
  return Number.isFinite(n) ? +n.toFixed(4) : 0;
};

// 雷达对比的指标集(量纲各异,radar 按各维 max 归一,故可混放 [0,1] 类与 novelty)。
const RADAR_METRICS = ['ndcg', 'precision', 'recall', 'map', 'mrr', 'hitrate', 'coverage', 'diversity', 'novelty'];

// eval/metrics-*.csv: variant,k,users,precision,recall,ndcg,map,mrr,hitrate,coverage,diversity,novelty
export default function EvalViz({ table }: { table: ReportTable }) {
  const objs = tableToObjects(table);
  const cats = objs.map((o) => `${o.variant}@${o.k}`);
  const metrics = ['ndcg', 'precision', 'recall', 'hitrate'];
  const series = metrics.map((m) => ({ name: m, data: objs.map((o) => safe(o[m])) }));

  // 雷达:选定一个 @K,每个 variant 一圈,跨指标看策略强弱侧写。
  const ks = useMemo(
    () => Array.from(new Set(objs.map((o) => o.k))).sort((a, b) => num(a) - num(b)),
    [objs],
  );
  const [k, setK] = useState<string | undefined>(undefined);
  const activeK = k && ks.includes(k) ? k : ks[0];

  const radar = useMemo(() => {
    if (!activeK) return null;
    const rows = objs.filter((o) => o.k === activeK);
    if (rows.length === 0) return null;
    // 各维 max 取「全体行(所有 K)」的最大,切换 K 时轴域稳定、便于横向比较。
    const indicators = RADAR_METRICS.map((m) => ({
      name: m,
      max: Math.max(0.0001, ...objs.map((o) => safe(o[m]))),
    }));
    const s = rows.map((o) => ({ name: o.variant, values: RADAR_METRICS.map((m) => safe(o[m])) }));
    return { indicators, series: s };
  }, [objs, activeK]);

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="eval" note="离线推荐质量:按时间切分留出集,复用 recall→rank" />
      {objs.length > 0 ? (
        <CollapsibleCard
          title="准确性指标 (NDCG / Precision / Recall / HitRate)"
          icon={<BarChartOutlined />}
          accent={ACCENTS.rank}
        >
          <EBar categories={cats} series={series} height={360} />
        </CollapsibleCard>
      ) : null}
      {radar ? (
        <CollapsibleCard
          title="多策略 × 多指标雷达"
          icon={<RadarChartOutlined />}
          accent={ACCENTS.recall}
          extra={
            <Space size={8}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                @K
              </Typography.Text>
              <Select
                size="small"
                style={{ width: 96 }}
                value={activeK}
                onChange={setK}
                options={ks.map((x) => ({ value: x, label: `@${x}` }))}
              />
            </Space>
          }
        >
          <ERadar indicators={radar.indicators} series={radar.series} height={420} />
        </CollapsibleCard>
      ) : null}
      <CollapsibleCard title="明细" icon={<TableOutlined />} accent={ACCENTS.rerank} defaultOpen={false}>
        <CsvTable table={table} />
      </CollapsibleCard>
    </Space>
  );
}
