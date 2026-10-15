import { Alert, Space, Tag } from 'antd';
import { DashboardOutlined, TableOutlined } from '@ant-design/icons';
import type { ReportTable } from '../../../api/types';
import { formatError } from '../../../api/errors';
import EGauge, { higherBetterBands, lowerBetterBands } from '../../charts/EGauge';
import CsvTable from '../CsvTable';
import CliCommandCard from '../CliCommandCard';
import CollapsibleCard from '../../CollapsibleCard';
import { ACCENTS } from '../../../theme/tokens';
import { inspectDataQuality } from '../dataQualityStatus';

// 数值与检查状态分开判断；失败/无数据项的原CSV仍可在明细中排查。
export default function DataQualityViz({ table }: { table: ReportTable }) {
  const report = inspectDataQuality(table);
  const gauges: {
    title: string;
    value: number;
    max: number;
    bands: [number, string][];
    fmt?: (v: number) => string;
  }[] = [];
  if (report.canVisualize('embedding')) {
    for (const [label, value] of [
      ['item embedding 覆盖', report.itemCoverage],
      ['user embedding 覆盖', report.userCoverage],
    ] as const) {
      gauges.push({
        title: label,
        value,
        max: 1,
        bands: higherBetterBands(0, 1, 0.9, 0.99),
        fmt: (x) => x.toFixed(3),
      });
    }
  }
  if (report.canVisualize('calibration')) {
    const max = Math.max(0.2, +(report.ece * 1.3).toFixed(3));
    gauges.push({
      title: 'pCTR 校准 ECE',
      value: report.ece,
      max,
      bands: lowerBetterBands(0, max, 0.05, 0.1),
      fmt: (x) => x.toFixed(3),
    });
  }
  if (report.canVisualize('category') && report.psiKey) {
    const max = Math.max(0.5, report.psi * 1.3);
    // 极大但有限的输入可能令仪表上界溢出；保留明细，不向图表传Infinity。
    if (Number.isFinite(max))
      gauges.push({
        title: report.psiKey,
        value: report.psi,
        max,
        bands: lowerBetterBands(0, max, 0.1, 0.25),
        fmt: (x) => x.toFixed(3),
      });
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <CliCommandCard job="data-quality" note="embedding 覆盖率 + pCTR 校准 ECE + 类目分布 PSI" />
      <Alert
        type={report.code ? 'warning' : 'success'}
        showIcon
        message={report.code ? formatError(report.code) : '三项检查已完成，无越阈值项'}
      />
      <Space wrap aria-label="巡检检查状态">
        {report.checks.map((check) => (
          <Tag key={check.key} color={check.color}>
            {check.label}：{check.stateLabel}
          </Tag>
        ))}
      </Space>
      <CollapsibleCard
        title="健康度仪表盘"
        icon={<DashboardOutlined />}
        accent={ACCENTS.rank}
        extra={
          <Tag color={report.code ? 'orange' : 'green'}>
            已记录越阈值 {report.breachCount ?? '未知'}
          </Tag>
        }
      >
        {gauges.length > 0 ? (
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {gauges.map((g) => (
              <div key={g.title} style={{ flex: '1 1 220px', minWidth: 0 }}>
                <EGauge
                  title={g.title}
                  value={g.value}
                  min={0}
                  max={g.max}
                  bands={g.bands}
                  format={g.fmt}
                  fileName={`dq-${g.title}.png`}
                />
              </div>
            ))}
          </div>
        ) : (
          <Alert type="info" showIcon message="暂无已完成且数值有效的检查，请查看各项状态与明细" />
        )}
      </CollapsibleCard>
      <CollapsibleCard
        title="明细"
        icon={<TableOutlined />}
        accent={ACCENTS.rerank}
        defaultOpen={false}
      >
        <CsvTable table={table} />
      </CollapsibleCard>
    </Space>
  );
}
