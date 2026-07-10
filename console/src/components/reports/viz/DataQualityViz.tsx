import { Alert, Card, Space, Statistic } from 'antd';
import type { ReportTable } from '../../../api/types';
import { num, tableToObjects } from '../../../api/report';
import CsvTable from '../CsvTable';
import CliCommandCard from '../CliCommandCard';

// eval/data-quality-*.csv: metric,value(长表)。metric=='breach' 的行是越阈值说明文本。
export default function DataQualityViz({ table }: { table: ReportTable }) {
  const objs = tableToObjects(table);
  const breaches = objs.filter((o) => o.metric === 'breach');
  const kv: Record<string, string> = {};
  objs.filter((o) => o.metric !== 'breach').forEach((o) => (kv[o.metric] = o.value));

  const psiKey = Object.keys(kv).find((k) => k.startsWith('category_psi_') && k.endsWith('d'));
  const stats: { label: string; key: string; suffix?: string }[] = [
    { label: 'item embedding 覆盖率', key: 'item_embedding_coverage' },
    { label: 'user embedding 覆盖率', key: 'user_embedding_coverage' },
    { label: 'pCTR 校准 ECE', key: 'pctr_ece' },
    ...(psiKey ? [{ label: psiKey, key: psiKey }] : []),
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="data-quality" note="embedding 覆盖率 + pCTR 校准 ECE + 类目分布 PSI" />
      <Card size="small" title="核心指标">
        <Space size={40} wrap>
          {stats.map((s) => {
            const v = num(kv[s.key]);
            return (
              <Statistic
                key={s.key}
                title={s.label}
                value={Number.isFinite(v) ? v : (kv[s.key] ?? '—')}
                precision={Number.isFinite(v) ? 4 : undefined}
              />
            );
          })}
          {kv.category_psi_level ? <Statistic title="PSI 等级" value={kv.category_psi_level} /> : null}
          {kv.breaches ? (
            <Statistic title="越阈值项" value={kv.breaches} valueStyle={{ color: num(kv.breaches) > 0 ? '#cf1322' : undefined }} />
          ) : null}
        </Space>
      </Card>
      {breaches.length > 0 ? (
        <Card size="small" title="越阈值告警">
          <Space direction="vertical" style={{ width: '100%' }}>
            {breaches.map((b, i) => (
              <Alert key={i} type="warning" showIcon message={b.value} />
            ))}
          </Space>
        </Card>
      ) : (
        <Alert type="success" showIcon message="无越阈值项" />
      )}
      <Card size="small" title="明细">
        <CsvTable table={table} />
      </Card>
    </Space>
  );
}
