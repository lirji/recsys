import { useQuery } from '@tanstack/react-query';
import { getReportFile, getReportIndex, num, tableToObjects, vizCategoryOf } from '../../api/report';
import type { ReportFileInfo } from '../../api/types';

// 只读复用 src/api/report.ts —— 不改后端契约。
// ab-report CSV 列(由离线 AbReportJob 写出,与 AbStats/AbViz 同源):
//   bucket,impressions,clicks,ctr,ctr_ci_low,ctr_ci_high,users,lift_vs_base,z,p_value,significant,min_sample_per_arm
// 旧格式只有 bucket,impressions,clicks,ctr,users(无 CI/显著性)—— 按列在与否降级。
// significant 空=基线桶;'true'=显著;'false'=不显著。lift/min_sample 在基线桶留空;min_sample 可为 'inf'。

export interface AbBucketRow {
  bucket: string;
  impressions: number;
  clicks: number;
  ctr: number;
  ciLow: number; // NaN 表示无 CI 列(旧格式)
  ciHigh: number;
  users: number;
  lift: number; // 相对基线的比例(如 0.05=+5%);基线桶为 NaN
  pValue: number; // NaN 表示无
  significant: boolean | null; // null=基线桶或无显著性列
  minSample: number; // 检测该 lift 的每臂最小样本量;NaN 表示无/inf
  minSampleInf: boolean; // true 表示原值为 'inf'(该 lift 太小,不可行)
  isBaseline: boolean; // 基线桶:曝光最多或 --baseline 指定;lift/significant 均空
}

export interface AbReportData {
  file: ReportFileInfo | null; // null=index 里没有 ab-report(还没跑过作业)
  rows: AbBucketRow[];
  hasSignificance: boolean; // 报表是否带 CI/显著性列(新格式)
}

function parseRow(o: Record<string, string>, hasSig: boolean): AbBucketRow {
  const sigRaw = o.significant;
  const liftRaw = o.lift_vs_base;
  const minRaw = o.min_sample_per_arm;
  const sigEmpty = sigRaw === undefined || sigRaw === '';
  const liftEmpty = liftRaw === undefined || liftRaw === '';
  return {
    bucket: o.bucket ?? '(none)',
    impressions: num(o.impressions),
    clicks: num(o.clicks),
    ctr: num(o.ctr),
    ciLow: num(o.ctr_ci_low),
    ciHigh: num(o.ctr_ci_high),
    users: num(o.users),
    lift: num(o.lift_vs_base),
    pValue: num(o.p_value),
    significant: sigEmpty ? null : sigRaw === 'true',
    minSample: minRaw === 'inf' ? NaN : num(o.min_sample_per_arm),
    minSampleInf: minRaw === 'inf',
    // 新格式里 significant+lift 同时为空即基线桶;旧格式无显著性概念,不标基线。
    isBaseline: hasSig && sigEmpty && liftEmpty,
  };
}

// 取数:index → 过滤 ab-report → 取最新一份(modifiedAt 优先,再按文件名时间戳)→ 解析。
// 无 ab-report → file=null(降级);网络/解析异常由 react-query 的 isError 暴露,调用方 catch 不崩页。
export function useAbReport() {
  return useQuery<AbReportData>({
    queryKey: ['ab-report-latest'],
    queryFn: async () => {
      const index = await getReportIndex();
      const abFiles = index
        .filter((f) => vizCategoryOf(f.fileName) === 'ab-report')
        .sort((a, b) => b.modifiedAt - a.modifiedAt || b.fileName.localeCompare(a.fileName));
      if (abFiles.length === 0) return { file: null, rows: [], hasSignificance: false };
      const file = abFiles[0];
      const table = await getReportFile(file.fileName);
      const hasSignificance =
        table.columns.includes('significant') || table.columns.includes('ctr_ci_low');
      const rows = tableToObjects(table).map((o) => parseRow(o, hasSignificance));
      return { file, rows, hasSignificance };
    },
    staleTime: 60_000,
    retry: 1,
  });
}

export interface VariantOnlineStat {
  impressions: number;
  clicks: number;
  ctr: number; // NaN 表示曝光为 0
  buckets: number; // 命中的完整分桶数(该变体跨其它层组合出多个桶)
  anySignificant: boolean; // 命中桶里是否有任一相对基线显著
}

// best-effort join:ab-report 按<b>完整</b>分桶(如 recall:base;rank:onnx;rerank:mmr)聚合,
// 而实验变体只是其中一层的一个 token(层:变体)。故对每个(层,变体)找 bucket 里含该 token 的所有桶,
// 跨桶聚合曝光/点击得该变体的边际在线 CTR。命名对不上(无匹配 token)→ 返回 null,由调用方标"无匹配"。
export function variantOnlineStat(
  rows: AbBucketRow[],
  layer: string,
  variant: string,
): VariantOnlineStat | null {
  const token = `${layer}:${variant}`;
  const matched = rows.filter((r) => r.bucket.split(';').some((t) => t.trim() === token));
  if (matched.length === 0) return null;
  const fin = (n: number) => (Number.isFinite(n) ? n : 0);
  const impressions = matched.reduce((s, r) => s + fin(r.impressions), 0);
  const clicks = matched.reduce((s, r) => s + fin(r.clicks), 0);
  return {
    impressions,
    clicks,
    ctr: impressions > 0 ? clicks / impressions : NaN,
    buckets: matched.length,
    anySignificant: matched.some((r) => r.significant === true),
  };
}

export function significantBuckets(rows: AbBucketRow[]): AbBucketRow[] {
  return rows.filter((r) => r.significant === true);
}
