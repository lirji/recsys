import { Space } from 'antd';
import { BarChartOutlined, DashboardOutlined, TableOutlined } from '@ant-design/icons';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import EBar from '../../charts/EBar';
import ERadar from '../../charts/ERadar';
import EGauge from '../../charts/EGauge';
import CsvTable from '../CsvTable';
import CliCommandCard from '../CliCommandCard';
import CollapsibleCard from '../../CollapsibleCard';
import { ACCENTS } from '../../../theme/tokens';

// eval/ad-quality-*.csv: ad_id,impr,clicks,conv,rel,ctr_shrunk,cvr_shrunk,relN,ctrN,cvrN,quality
export default function AdQualityViz({ table }: { table: ReportTable }) {
  const all = tableToObjects(table);
  const objs = all.slice(0, 30);
  const cats = objs.map((o) => `#${o.ad_id}`);
  const n = (o: Record<string, string>, k: string) => {
    const v = num(o[k]);
    return Number.isFinite(v) ? +v.toFixed(4) : 0;
  };

  // 平均质量度乘子(围绕 1.0):离线按贝叶斯收缩把三因子归一融合成 eCPM 乘子。
  const qs = all.map((o) => num(o.quality)).filter((v) => Number.isFinite(v));
  const avgQ = qs.length ? qs.reduce((a, b) => a + b, 0) / qs.length : 0;
  // 1.0 为中性;偏离两侧同色带,健康区(0.85~1.15)绿。
  const qBands: [number, string][] = [
    [0.425, ACCENTS.ad],
    [0.575, ACCENTS.gsp],
    [1, ACCENTS.ad],
  ];

  // 三因子雷达:曝光最多的前 6 条广告(样本更足更可信),relN/ctrN/cvrN 三轴。
  const topByImpr = [...all]
    .filter((o) => Number.isFinite(num(o.ad_id)))
    .sort((a, b) => num(b.impr) - num(a.impr))
    .slice(0, 6);
  const factorMax = (k: string) => Math.max(0.0001, ...all.map((o) => n(o, k)));
  const radarIndicators = [
    { name: 'relN', max: factorMax('relN') },
    { name: 'ctrN', max: factorMax('ctrN') },
    { name: 'cvrN', max: factorMax('cvrN') },
  ];
  const radarSeries = topByImpr.map((o) => ({
    name: `#${o.ad_id}`,
    values: [n(o, 'relN'), n(o, 'ctrN'), n(o, 'cvrN')],
  }));

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="ad-quality" note="相关性/CTR/CVR 三因子贝叶斯收缩融合成质量度乘子(围绕 1.0)" />
      {qs.length > 0 || radarSeries.length > 0 ? (
        <CollapsibleCard title="质量度概览" icon={<DashboardOutlined />} accent={ACCENTS.ad}>
          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', alignItems: 'center' }}>
            {qs.length > 0 ? (
              <div style={{ flex: '0 0 220px' }}>
                <EGauge
                  value={+avgQ.toFixed(3)}
                  min={0}
                  max={2}
                  title={`平均质量度 (n=${qs.length})`}
                  bands={qBands}
                  format={(v) => v.toFixed(3)}
                  fileName="ad-quality-avg.png"
                />
              </div>
            ) : null}
            {radarSeries.length > 0 ? (
              <div style={{ flex: '1 1 340px', minWidth: 300 }}>
                <ERadar indicators={radarIndicators} series={radarSeries} height={340} fileName="ad-quality-radar.png" />
              </div>
            ) : null}
          </div>
        </CollapsibleCard>
      ) : null}
      {objs.length > 0 ? (
        <CollapsibleCard title="质量度因子(前 30 条)" icon={<BarChartOutlined />} accent={ACCENTS.rank}>
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
        </CollapsibleCard>
      ) : null}
      <CollapsibleCard title="明细" icon={<TableOutlined />} accent={ACCENTS.rerank} defaultOpen={false}>
        <CsvTable table={table} />
      </CollapsibleCard>
    </Space>
  );
}
