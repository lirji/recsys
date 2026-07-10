import { http } from './client';
import type { ReportFileInfo, ReportTable } from './types';

export async function getReportIndex(): Promise<ReportFileInfo[]> {
  const { data } = await http.get<ReportFileInfo[]>('/api/console/report/index');
  return data;
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
