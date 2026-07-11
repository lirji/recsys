import { http } from './client';
import type { ReportFileInfo, ReportTable } from './types';

export async function getReportIndex(): Promise<ReportFileInfo[]> {
  const { data } = await http.get<ReportFileInfo[]>('/api/console/report/index');
  return data;
}

// 前端按「文件名前缀」派生更细的可视化分类 —— 后端 ReportService 只识别 5 个前缀,
// 其余(ad-attribution / ad-delay / bandit)统一落 "other"。这里补全,让隐藏报表也能 dispatch 到专属 viz。
// 与 recsys-console ReportService.PREFIX 保持同一套前缀口径。
export type VizCategory =
  | 'eval'
  | 'ab-report'
  | 'ad-report'
  | 'data-quality'
  | 'ad-quality'
  | 'ad-attribution'
  | 'ad-delay'
  | 'bandit'
  | 'other';

const VIZ_PREFIX: [string, VizCategory][] = [
  ['metrics-', 'eval'],
  ['ab-report-', 'ab-report'],
  ['ad-report-', 'ad-report'],
  ['data-quality-', 'data-quality'],
  ['ad-quality-', 'ad-quality'],
  ['ad-attribution-', 'ad-attribution'],
  ['ad-delay-', 'ad-delay'],
  ['bandit-', 'bandit'],
];

export function vizCategoryOf(fileName: string): VizCategory {
  for (const [prefix, cat] of VIZ_PREFIX) {
    if (fileName.startsWith(prefix)) return cat;
  }
  return 'other';
}

export async function getReportFile(name: string): Promise<ReportTable> {
  const { data } = await http.get<ReportTable>('/api/console/report/file', { params: { name } });
  return data;
}

// 把 {columns, rows} 转成对象数组,便于按列名取值。
export function tableToObjects(t: ReportTable): Record<string, string>[] {
  return t.rows.map((r) => {
    const o: Record<string, string> = {};
    t.columns.forEach((c, i) => (o[c] = r[i] ?? ''));
    return o;
  });
}

export function num(v: string | undefined): number {
  if (v === undefined || v === '' || v === 'inf') return NaN;
  const n = Number(v);
  return Number.isFinite(n) ? n : NaN;
}
