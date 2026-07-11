import { Space } from 'antd';
import { BarChartOutlined, DashboardOutlined, FilterOutlined, TableOutlined } from '@ant-design/icons';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import EBar from '../../charts/EBar';
import EFunnel from '../../charts/EFunnel';
import EGauge, { higherBetterBands } from '../../charts/EGauge';
import CsvTable from '../CsvTable';
import CliCommandCard from '../CliCommandCard';
import CollapsibleCard from '../../CollapsibleCard';
import { ACCENTS } from '../../../theme/tokens';

// eval/ad-report-*.csv: position,impressions,clicks,conversions,revenue,ctr,cvr,ecpm,avg_relevance
// 同文件混有 position 数字行 + ALL 汇总行 + bucket:* 分桶行 —— 图只取数字 position 行,funnel/gauge 取 ALL(缺则由位次行求和)。
export default function AdReportViz({ table }: { table: ReportTable }) {
  const objs = tableToObjects(table);
  const posRows = objs.filter((o) => /^\d+$/.test((o.position ?? '').trim()));
  const cats = posRows.map((o) => `pos ${o.position}`);
  const n = (o: Record<string, string>, k: string) => {
    const v = num(o[k]);
    return Number.isFinite(v) ? v : 0;
  };

  const allRow = objs.find((o) => (o.position ?? '').trim().toUpperCase() === 'ALL');
  const sum = (k: string) => posRows.reduce((a, o) => a + n(o, k), 0);
  const impr = allRow ? n(allRow, 'impressions') : sum('impressions');
  const clicks = allRow ? n(allRow, 'clicks') : sum('clicks');
  const conv = allRow ? n(allRow, 'conversions') : sum('conversions');
  const ctr = impr > 0 ? clicks / impr : 0;
  const cvr = clicks > 0 ? conv / clicks : 0;
  const ecpm = allRow ? n(allRow, 'ecpm') : 0;
  const maxEcpm = Math.max(ecpm, ...posRows.map((o) => n(o, 'ecpm')), 1);
  const ctrMax = Math.max(50, Math.ceil(ctr * 100 + 10));
  const cvrMax = Math.max(50, Math.ceil(cvr * 100 + 10));

  const hasFunnel = impr > 0 || clicks > 0 || conv > 0;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="ad-report" note="按广告位次聚合曝光/点击/转化/收入/eCPM" />
      {hasFunnel ? (
        <CollapsibleCard title="变现漏斗:曝光 → 点击 → 转化" icon={<FilterOutlined />} accent={ACCENTS.ad}>
          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', alignItems: 'center' }}>
            <div style={{ flex: '1 1 360px', minWidth: 300 }}>
              <EFunnel
                stages={[
                  { name: '曝光', value: impr },
                  { name: '点击', value: clicks },
                  { name: '转化', value: conv },
                ]}
                fileName="ad-funnel.png"
              />
            </div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', flex: '1 1 300px' }}>
              <EGauge
                value={+(ctr * 100).toFixed(2)}
                max={ctrMax}
                title="整体 CTR %"
                bands={higherBetterBands(0, ctrMax, 5, 15)}
                format={(v) => v.toFixed(2) + '%'}
                fileName="ad-ctr.png"
              />
              <EGauge
                value={+(cvr * 100).toFixed(2)}
                max={cvrMax}
                title="整体 CVR %"
                bands={higherBetterBands(0, cvrMax, 5, 15)}
                format={(v) => v.toFixed(2) + '%'}
                fileName="ad-cvr.png"
              />
              <EGauge
                value={+ecpm.toFixed(2)}
                max={+(maxEcpm * 1.25).toFixed(0)}
                title="整体 eCPM"
                format={(v) => v.toFixed(1)}
                fileName="ad-ecpm.png"
              />
            </div>
          </div>
        </CollapsibleCard>
      ) : null}
      {posRows.length > 0 ? (
        <>
          <CollapsibleCard title="逐位次:曝光 / 点击 / 转化" icon={<BarChartOutlined />} accent={ACCENTS.rank}>
            <EBar
              categories={cats}
              series={[
                { name: '曝光', data: posRows.map((o) => n(o, 'impressions')) },
                { name: '点击', data: posRows.map((o) => n(o, 'clicks')) },
                { name: '转化', data: posRows.map((o) => n(o, 'conversions')) },
              ]}
            />
          </CollapsibleCard>
          <CollapsibleCard title="逐位次:eCPM / 收入" icon={<DashboardOutlined />} accent={ACCENTS.recall}>
            <EBar
              categories={cats}
              series={[
                { name: 'eCPM', data: posRows.map((o) => +n(o, 'ecpm').toFixed(4)) },
                { name: '收入', data: posRows.map((o) => +n(o, 'revenue').toFixed(4)) },
              ]}
            />
          </CollapsibleCard>
        </>
      ) : null}
      <CollapsibleCard
        title="明细(含 ALL 汇总 / bucket 分桶行)"
        icon={<TableOutlined />}
        accent={ACCENTS.rerank}
        defaultOpen={false}
      >
        <CsvTable table={table} />
      </CollapsibleCard>
    </Space>
  );
}
