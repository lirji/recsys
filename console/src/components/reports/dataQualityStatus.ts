import { tableToObjects } from '../../api/report';
import type { ReportTable } from '../../api/types';
import type { KnownErrorCode } from '../../api/generated/errorCodes';

// 与common/report/DataQualityCheck的CSV稳定键一致；未知编码只能作为未知状态。
const checks = [
  { id: 'embedding', key: 'check_embedding_status', label: '向量覆盖率' },
  { id: 'calibration', key: 'check_calibration_status', label: '校准偏差' },
  { id: 'category', key: 'check_category_status', label: '类目分布' },
] as const;

const CheckState = {
  PASSED: 'PASSED',
  BREACHED: 'BREACHED',
  NO_DATA: 'NO_DATA',
  FAILED: 'FAILED',
  UNKNOWN: 'UNKNOWN',
} as const;

const states = {
  [CheckState.PASSED]: { label: '通过', color: 'green' },
  [CheckState.BREACHED]: { label: '越阈值', color: 'orange' },
  [CheckState.NO_DATA]: { label: '缺少数据', color: 'orange' },
  [CheckState.FAILED]: { label: '检查失败', color: 'red' },
  [CheckState.UNKNOWN]: { label: '状态未知', color: 'orange' },
} as const;

type CheckStatus = keyof typeof states;

/** 报表数值空白不转0，检查状态和实际指标必须同时可用。 */
export function inspectDataQuality(table: ReportTable) {
  const values: Record<string, string> = {};
  const duplicates = new Set<string>();
  const rows = tableToObjects(table);
  for (const row of rows.filter((row) => row.metric !== 'breach')) {
    if (Object.prototype.hasOwnProperty.call(values, row.metric)) duplicates.add(row.metric);
    values[row.metric] = row.value;
  }
  const number = (key: string): number => {
    const value = values[key];
    if (duplicates.has(key) || !value?.trim()) return NaN;
    return Number(value);
  };
  const ratio = (value: number) => Number.isFinite(value) && value >= 0 && value <= 1;
  const psiKeys = Object.keys(values).filter((key) => /^category_psi_-?[0-9]+d$/.test(key));
  const psiKey = psiKeys.length === 1 ? psiKeys[0] : undefined;
  const itemCoverage = number('item_embedding_coverage');
  const userCoverage = number('user_embedding_coverage');
  const ece = number('pctr_ece');
  const psi = psiKey ? number(psiKey) : NaN;
  const validMetrics = {
    embedding: ratio(itemCoverage) && ratio(userCoverage),
    calibration: ratio(ece),
    category: Number.isFinite(psi) && psi >= 0,
  };
  const results = checks.map((check) => {
    const raw = values[check.key];
    let status: CheckStatus =
      !duplicates.has(check.key) &&
      Object.prototype.hasOwnProperty.call(states, raw) &&
      raw !== CheckState.UNKNOWN
        ? (raw as CheckStatus)
        : CheckState.UNKNOWN;
    const complete = status === CheckState.PASSED || status === CheckState.BREACHED;
    if (complete && !validMetrics[check.id]) status = CheckState.UNKNOWN;
    return {
      ...check,
      status,
      stateLabel: states[status].label,
      color: states[status].color,
      usable: complete && validMetrics[check.id],
    };
  });
  const rawCount = number('breaches');
  const breachCount = Number.isSafeInteger(rawCount) && rawCount >= 0 ? rawCount : undefined;
  const failed = results.some((check) => check.status === CheckState.FAILED);
  const incomplete = results.some((check) => !check.usable) || breachCount === undefined;
  const breached =
    results.some((check) => check.status === CheckState.BREACHED) ||
    (breachCount ?? 0) > 0 ||
    rows.some((row) => row.metric === 'breach');
  const code: KnownErrorCode | undefined = failed
    ? 'JOB_DATA_QUALITY_CHECK_FAILED'
    : incomplete
      ? 'JOB_DATA_QUALITY_INCOMPLETE'
      : breached
        ? 'JOB_DATA_QUALITY_BREACHED'
        : undefined;
  return {
    checks: results,
    canVisualize: (id: (typeof checks)[number]['id']) =>
      results.some((check) => check.id === id && check.usable),
    itemCoverage,
    userCoverage,
    ece,
    psi,
    psiKey,
    breachCount,
    code,
  };
}
