import { Space, Typography } from 'antd';
import { BarChartOutlined, PartitionOutlined, SwapOutlined, TableOutlined } from '@ant-design/icons';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import EBar from '../../charts/EBar';
import ChartFrame from '../../charts/ChartFrame';
import CsvTable from '../CsvTable';
import CliCommandCard from '../CliCommandCard';
import CollapsibleCard from '../../CollapsibleCard';
import { ACCENTS, rgba } from '../../../theme/tokens';
import { barGradient } from '../../charts/echartsTheme';

// eval/ad-attribution-*.csv: ad_id,conversions_last_touch,mta_credit,cta_credit,vta_credit,credit_delta
// last-touch(末次归因)vs MTA(多触点)分配的信用对比 + 点击后(CTA)/浏览后(VTA)拆分 + credit_delta(MTA−lastTouch)。
export default function AttributionViz({ table }: { table: ReportTable }) {
  const all = tableToObjects(table);
  const n = (o: Record<string, string>, k: string) => {
    const v = num(o[k]);
    return Number.isFinite(v) ? v : 0;
  };
  // 取信用体量最大的前 24 条广告(按 last-touch 与 MTA 的较大者),避免长尾把图压扁。
  const objs = [...all]
    .sort((a, b) => Math.max(n(b, 'conversions_last_touch'), n(b, 'mta_credit')) - Math.max(n(a, 'conversions_last_touch'), n(a, 'mta_credit')))
    .slice(0, 24);
  const cats = objs.map((o) => `#${o.ad_id}`);
  const r4 = (v: number) => +v.toFixed(4);

  const deltas = objs.map((o) => r4(n(o, 'credit_delta')));
  // credit_delta 分正负发散上色:MTA 相对末次「多分到」信用为绿,「被稀释」为红。
  const deltaOption = {
    tooltip: { trigger: 'axis' as const, axisPointer: { type: 'shadow' as const } },
    grid: { left: 52, right: 20, top: 20, bottom: 48 },
    xAxis: { type: 'category' as const, data: cats, axisLabel: { interval: 0, rotate: cats.length > 8 ? 30 : 0 } },
    yAxis: { type: 'value' as const, name: 'ΔCredit' },
    series: [
      {
        name: 'credit_delta (MTA − 末次)',
        type: 'bar' as const,
        data: deltas.map((v) => ({
          value: v,
          itemStyle: { color: v >= 0 ? barGradient(ACCENTS.gsp) : barGradient(ACCENTS.warn), borderRadius: [3, 3, 0, 0] as [number, number, number, number] },
        })),
        markLine: { silent: true, symbol: 'none', data: [{ yAxis: 0 }], lineStyle: { color: rgba('#3b4657', 0.5) } },
      },
    ],
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="ad-attribution" note="多触点归因 MTA:末次 vs 摊分信用 + CTA/VTA 拆分(纯统计报表,不进计费)" />
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        MTA 用 linear / position(U 形)/ time-decay 把每次转化的 1.0 信用摊到路径各触点;末次归因把 1.0 全给最后一次点击。
        对比二者可见「被末次高估 / 低估」的广告。
      </Typography.Paragraph>
      {objs.length > 0 ? (
        <>
          <CollapsibleCard title="末次归因 vs MTA 信用" icon={<BarChartOutlined />} accent={ACCENTS.ad}>
            <EBar
              categories={cats}
              series={[
                { name: '末次归因', data: objs.map((o) => r4(n(o, 'conversions_last_touch'))) },
                { name: 'MTA 信用', data: objs.map((o) => r4(n(o, 'mta_credit'))) },
              ]}
              yName="信用"
              height={340}
            />
          </CollapsibleCard>
          <CollapsibleCard title="CTA(点击后) / VTA(浏览后)拆分" icon={<PartitionOutlined />} accent={ACCENTS.rank}>
            <EBar
              categories={cats}
              series={[
                { name: 'CTA (点击后)', data: objs.map((o) => r4(n(o, 'cta_credit'))) },
                { name: 'VTA (浏览后)', data: objs.map((o) => r4(n(o, 'vta_credit'))) },
              ]}
              yName="信用"
              height={340}
            />
          </CollapsibleCard>
          <CollapsibleCard title="credit_delta:MTA 相对末次的信用位移(绿=多分 / 红=稀释)" icon={<SwapOutlined />} accent={ACCENTS.rerank}>
            <ChartFrame option={deltaOption} height={320} fileName="attribution-delta.png" />
          </CollapsibleCard>
        </>
      ) : null}
      <CollapsibleCard title="明细" icon={<TableOutlined />} accent={ACCENTS.rerank} defaultOpen={false}>
        <CsvTable table={table} />
      </CollapsibleCard>
    </Space>
  );
}
