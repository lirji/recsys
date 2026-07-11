import { Alert, Space, Statistic, Typography } from 'antd';
import { LineChartOutlined, TableOutlined } from '@ant-design/icons';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import ELine from '../../charts/ELine';
import EBar from '../../charts/EBar';
import CsvTable from '../CsvTable';
import CliCommandCard from '../CliCommandCard';
import CollapsibleCard from '../../CollapsibleCard';
import { ACCENTS } from '../../../theme/tokens';

// ad-delay:延迟反馈建模。DelayModelJob 目前只把 λ 落 Redis(ad:delay:lambda),暂无标准 CSV;
// 本 viz 做「格式自适应」——若 CSV 里能取到 λ(或 mean 延迟,λ=1/mean),就画完成曲线 c(e)=1−e^(−λe);
// 若是 (延迟分桶, 计数) 两列分布,则画直方图;都取不到就退明细表。不臆造固定列名。
export default function DelayViz({ table }: { table: ReportTable }) {
  const objs = tableToObjects(table);

  // 扁平 kv:两列且首列像 metric/key → 长表;否则取首行当宽表。
  const flat: Record<string, string> = {};
  const c0 = (table.columns[0] ?? '').toLowerCase();
  if (table.columns.length === 2 && ['metric', 'key', 'name', 'stat'].includes(c0)) {
    objs.forEach((o) => (flat[String(o[table.columns[0]]).toLowerCase()] = o[table.columns[1]]));
  } else if (objs[0]) {
    table.columns.forEach((c) => (flat[c.toLowerCase()] = objs[0][c]));
  }
  const pick = (...keys: string[]) => {
    for (const k of keys) {
      const v = num(flat[k]);
      if (Number.isFinite(v)) return v;
    }
    return NaN;
  };

  let lambda = pick('lambda', 'ad_delay_lambda', 'lambda_per_day');
  const mean = pick('mean_delay_days', 'mean_days', 'mean', 'avg_delay_days');
  if (!Number.isFinite(lambda) && Number.isFinite(mean) && mean > 0) lambda = 1 / mean;
  const maxDay = Math.max(14, Math.round(pick('max_delay_days') || 14));

  // (延迟, 计数) 两列分布探测:两数值列,首列升序整数天。
  const distCols =
    table.columns.length >= 2 &&
    objs.length > 1 &&
    objs.every((o) => Number.isFinite(num(o[table.columns[0]])) && Number.isFinite(num(o[table.columns[1]])));

  const curve = Number.isFinite(lambda)
    ? Array.from({ length: maxDay + 1 }, (_, e) => +(1 - Math.exp(-lambda * e)).toFixed(4))
    : null;
  const cAt = (e: number) => (Number.isFinite(lambda) ? +(1 - Math.exp(-lambda * e)).toFixed(4) : NaN);

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="ad-delay" note="延迟转化建模:指数分布 MLE λ=1/mean → 完成曲线 c(e)=1−e^(−λe),供 oCPC Horvitz–Thompson 纠偏" />
      {curve ? (
        <CollapsibleCard title="转化完成曲线 c(e) = 1 − e^(−λe)" icon={<LineChartOutlined />} accent={ACCENTS.gsp}>
          <Space size={40} wrap style={{ marginBottom: 8 }}>
            <Statistic title="λ (1/天)" value={+lambda.toFixed(6)} />
            {Number.isFinite(mean) ? <Statistic title="平均延迟(天)" value={+mean.toFixed(3)} /> : null}
            <Statistic title="c(1d)" value={cAt(1)} precision={4} />
            <Statistic title="c(3d)" value={cAt(3)} precision={4} />
            <Statistic title="c(7d)" value={cAt(7)} precision={4} />
            <Statistic title="c(14d)" value={cAt(14)} precision={4} />
          </Space>
          <ELine
            categories={Array.from({ length: maxDay + 1 }, (_, e) => `${e}d`)}
            series={[{ name: 'c(e) 已到达比例', data: curve }]}
            yName="完成比例"
            area
            fileName="delay-completion.png"
          />
        </CollapsibleCard>
      ) : null}
      {distCols ? (
        <CollapsibleCard title="延迟分布(按首两列)" icon={<LineChartOutlined />} accent={ACCENTS.ad}>
          <EBar
            categories={objs.map((o) => String(o[table.columns[0]]))}
            series={[{ name: table.columns[1], data: objs.map((o) => num(o[table.columns[1]]) || 0) }]}
          />
        </CollapsibleCard>
      ) : null}
      {!curve && !distCols ? (
        <Alert
          type="info"
          showIcon
          message="未识别到 λ / 延迟分布列"
          description={
            <Typography.Text type="secondary">
              DelayModelJob 目前只把 λ 写入 Redis <span className="mono">ad:delay:lambda</span>,尚未产出标准 CSV。
              当报表含 <span className="mono">lambda</span> / <span className="mono">mean_delay_days</span> 或(延迟,计数)两列时,此处自动出图。
            </Typography.Text>
          }
        />
      ) : null}
      <CollapsibleCard title="明细" icon={<TableOutlined />} accent={ACCENTS.rerank} defaultOpen={false}>
        <CsvTable table={table} />
      </CollapsibleCard>
    </Space>
  );
}
