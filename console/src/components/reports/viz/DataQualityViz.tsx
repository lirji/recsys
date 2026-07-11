import { Alert, Space, Tag } from 'antd';
import { DashboardOutlined, TableOutlined, WarningOutlined } from '@ant-design/icons';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import EGauge, { higherBetterBands, lowerBetterBands } from '../../charts/EGauge';
import CsvTable from '../CsvTable';
import CliCommandCard from '../CliCommandCard';
import CollapsibleCard from '../../CollapsibleCard';
import { ACCENTS } from '../../../theme/tokens';

// eval/data-quality-*.csv: metric,value(长表)。metric=='breach' 的行是越阈值说明文本。
export default function DataQualityViz({ table }: { table: ReportTable }) {
  const objs = tableToObjects(table);
  const breaches = objs.filter((o) => o.metric === 'breach');
  const kv: Record<string, string> = {};
  objs.filter((o) => o.metric !== 'breach').forEach((o) => (kv[o.metric] = o.value));

  const psiKey = Object.keys(kv).find((k) => k.startsWith('category_psi_') && k.endsWith('d'));

  // 健康度仪表:覆盖率越高越好(绿在右);ECE / PSI 越低越好(绿在左),阈值取巡检默认(ECE 0.05、PSI 0.1/0.25)。
  const gauges: { title: string; value: number; max: number; bands: [number, string][]; fmt?: (v: number) => string }[] = [];
  const cov = (key: string, label: string) => {
    const v = num(kv[key]);
    if (Number.isFinite(v)) {
      gauges.push({ title: label, value: v, max: 1, bands: higherBetterBands(0, 1, 0.9, 0.99), fmt: (x) => x.toFixed(3) });
    }
  };
  cov('item_embedding_coverage', 'item embedding 覆盖');
  cov('user_embedding_coverage', 'user embedding 覆盖');
  const ece = num(kv['pctr_ece']);
  if (Number.isFinite(ece)) {
    const max = Math.max(0.2, +(ece * 1.3).toFixed(3));
    gauges.push({ title: 'pCTR 校准 ECE', value: ece, max, bands: lowerBetterBands(0, max, 0.05, 0.1), fmt: (x) => x.toFixed(3) });
  }
  if (psiKey) {
    const v = num(kv[psiKey]);
    if (Number.isFinite(v)) {
      const max = Math.max(0.5, +(v * 1.3).toFixed(3));
      gauges.push({ title: psiKey, value: v, max, bands: lowerBetterBands(0, max, 0.1, 0.25), fmt: (x) => x.toFixed(3) });
    }
  }

  const breachCount = num(kv.breaches);

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="data-quality" note="embedding 覆盖率 + pCTR 校准 ECE + 类目分布 PSI" />
      <CollapsibleCard
        title="健康度仪表盘"
        icon={<DashboardOutlined />}
        accent={ACCENTS.rank}
        extra={
          <Space size={6}>
            {kv.category_psi_level ? <Tag color="blue">PSI {kv.category_psi_level}</Tag> : null}
            <Tag color={Number.isFinite(breachCount) && breachCount > 0 ? 'red' : 'green'}>
              越阈值 {Number.isFinite(breachCount) ? breachCount : (kv.breaches ?? 0)}
            </Tag>
          </Space>
        }
      >
        {gauges.length > 0 ? (
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {gauges.map((g) => (
              <EGauge
                key={g.title}
                title={g.title}
                value={g.value}
                min={0}
                max={g.max}
                bands={g.bands}
                format={g.fmt}
                fileName={`dq-${g.title}.png`}
              />
            ))}
          </div>
        ) : (
          <Alert type="info" showIcon message="无可视化的数值指标(该报表仅含文本项)" />
        )}
      </CollapsibleCard>
      {breaches.length > 0 ? (
        <CollapsibleCard title="越阈值告警" icon={<WarningOutlined />} accent={ACCENTS.warn}>
          <Space direction="vertical" style={{ width: '100%' }}>
            {breaches.map((b, i) => (
              <Alert key={i} type="warning" showIcon message={b.value} />
            ))}
          </Space>
        </CollapsibleCard>
      ) : (
        <Alert type="success" showIcon message="无越阈值项" />
      )}
      <CollapsibleCard title="明细" icon={<TableOutlined />} accent={ACCENTS.rerank} defaultOpen={false}>
        <CsvTable table={table} />
      </CollapsibleCard>
    </Space>
  );
}
