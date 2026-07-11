import { useMemo, useState } from 'react';
import { useQueries } from '@tanstack/react-query';
import { Alert, Select, Space, Typography } from 'antd';
import type { ReportFileInfo, ReportTable } from '../../api/types';
import { getReportFile, num, tableToObjects, type VizCategory } from '../../api/report';
import { ChartSkeleton } from '../Skeletons';
import ELine, { type LineSeries } from '../charts/ELine';

type Objs = Record<string, string>[];

// 跨时间戳趋势对比:选中同类 ≥2 份报表 → 并排折线看指标随离线作业迭代的走向。
// 不同报表类型的"可比指标"不同,故按 category 分派构造折线数据。
export default function ReportCompare({ category, files }: { category: VizCategory; files: ReportFileInfo[] }) {
  // 时间戳升序,x 轴从旧到新。
  const sorted = useMemo(() => [...files].sort((a, b) => a.timestamp.localeCompare(b.timestamp)), [files]);
  const results = useQueries({
    queries: sorted.map((f) => ({
      queryKey: ['report-file', f.fileName],
      queryFn: () => getReportFile(f.fileName),
      staleTime: 60_000,
    })),
  });

  const ready = results.every((r) => r.isSuccess);
  const loading = results.some((r) => r.isLoading);
  const errored = results.some((r) => r.isError);

  const xs = sorted.map((f) => f.timestamp);
  const tables = ready ? (results.map((r) => r.data) as ReportTable[]) : [];
  const perFile: Objs[] = tables.map((t) => tableToObjects(t));

  // ---- eval:选 metric + @K,一条线 = 一个 variant ----
  const evalMeta = useMemo(() => {
    if (category !== 'eval') return null;
    const variants = new Set<string>();
    const ks = new Set<string>();
    perFile.forEach((objs) => objs.forEach((o) => { variants.add(o.variant); ks.add(o.k); }));
    return { variants: [...variants], ks: [...ks].sort((a, b) => num(a) - num(b)) };
  }, [category, perFile]);
  const evalMetrics = ['ndcg', 'precision', 'recall', 'map', 'mrr', 'hitrate', 'coverage', 'diversity', 'novelty'];
  const [evalMetric, setEvalMetric] = useState('ndcg');
  const [evalK, setEvalK] = useState<string | undefined>(undefined);

  // ---- ad-report:选 metric,一条线 = 一个位次(+ALL)----
  const adMetrics = [
    { v: 'ctr', pct: true }, { v: 'cvr', pct: true }, { v: 'ecpm', pct: false },
    { v: 'revenue', pct: false }, { v: 'impressions', pct: false }, { v: 'clicks', pct: false }, { v: 'conversions', pct: false },
  ];
  const [adMetric, setAdMetric] = useState('ctr');

  const built = useMemo<{ series: LineSeries[]; yName?: string } | null>(() => {
    if (!ready || perFile.length === 0) return null;
    const val = (objs: Objs, pred: (o: Objs[number]) => boolean, key: string, scale = 1): number | null => {
      const o = objs.find(pred);
      if (!o) return null;
      const v = num(o[key]);
      return Number.isFinite(v) ? +(v * scale).toFixed(4) : null;
    };

    if (category === 'eval' && evalMeta) {
      const k = evalK && evalMeta.ks.includes(evalK) ? evalK : evalMeta.ks[evalMeta.ks.length - 1];
      const series = evalMeta.variants.map((variant) => ({
        name: variant,
        data: perFile.map((objs) => val(objs, (o) => o.variant === variant && o.k === k, evalMetric)),
      }));
      return { series, yName: `${evalMetric}@${k}` };
    }
    if (category === 'ab-report') {
      const buckets = new Set<string>();
      perFile.forEach((objs) => objs.forEach((o) => buckets.add(o.bucket)));
      const series = [...buckets].map((b) => ({
        name: b,
        data: perFile.map((objs) => val(objs, (o) => o.bucket === b, 'ctr', 100)),
      }));
      return { series, yName: 'CTR %' };
    }
    if (category === 'ad-report') {
      const meta = adMetrics.find((m) => m.v === adMetric)!;
      const positions = new Set<string>(['ALL']);
      perFile.forEach((objs) => objs.forEach((o) => { if (/^\d+$/.test((o.position ?? '').trim())) positions.add(o.position); }));
      const scale = meta.pct ? 100 : 1;
      const series = [...positions].map((p) => ({
        name: p === 'ALL' ? 'ALL' : `pos ${p}`,
        data: perFile.map((objs) => val(objs, (o) => (o.position ?? '').trim() === p, adMetric, scale)),
      }));
      return { series, yName: meta.pct ? `${adMetric} %` : adMetric };
    }
    if (category === 'data-quality') {
      const flat = (objs: Objs) => {
        const m: Record<string, string> = {};
        objs.filter((o) => o.metric !== 'breach').forEach((o) => (m[o.metric] = o.value));
        return m;
      };
      const maps = perFile.map(flat);
      const psiKey = maps.map((m) => Object.keys(m).find((k) => k.startsWith('category_psi_') && k.endsWith('d'))).find(Boolean);
      const keys = ['item_embedding_coverage', 'user_embedding_coverage', 'pctr_ece', ...(psiKey ? [psiKey] : [])];
      const series = keys.map((key) => ({
        name: key,
        data: maps.map((m) => { const v = num(m[key]); return Number.isFinite(v) ? +v.toFixed(4) : null; }),
      }));
      return { series, yName: '指标值' };
    }
    if (category === 'ad-quality') {
      const mean = (objs: Objs, key: string): number | null => {
        const vs = objs.map((o) => num(o[key])).filter((v) => Number.isFinite(v));
        return vs.length ? +(vs.reduce((a, b) => a + b, 0) / vs.length).toFixed(4) : null;
      };
      const series = ['quality', 'relN', 'ctrN', 'cvrN'].map((key) => ({
        name: `均值 ${key}`,
        data: perFile.map((objs) => mean(objs, key)),
      }));
      return { series, yName: '均值' };
    }
    if (category === 'ad-attribution') {
      const sumOf = (objs: Objs, key: string): number | null => {
        const s = objs.reduce((a, o) => a + (Number.isFinite(num(o[key])) ? num(o[key]) : 0), 0);
        return +s.toFixed(2);
      };
      const series = [
        { name: '末次归因合计', data: perFile.map((objs) => sumOf(objs, 'conversions_last_touch')) },
        { name: 'MTA 信用合计', data: perFile.map((objs) => sumOf(objs, 'mta_credit')) },
      ];
      return { series, yName: '信用合计' };
    }
    return null;
  }, [ready, perFile, category, evalMeta, evalK, evalMetric, adMetric]);

  if (loading) return <ChartSkeleton height={320} />;
  if (errored) return <Alert type="error" showIcon message="部分对比文件加载失败" />;
  if (!built) {
    return <Alert type="info" showIcon message="该报表类型暂不支持趋势对比" />;
  }

  const controls =
    category === 'eval' && evalMeta ? (
      <Space size={8} wrap>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>指标</Typography.Text>
        <Select size="small" style={{ width: 120 }} value={evalMetric} onChange={setEvalMetric}
          options={evalMetrics.map((m) => ({ value: m, label: m }))} />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>@K</Typography.Text>
        <Select size="small" style={{ width: 90 }} value={evalK ?? evalMeta.ks[evalMeta.ks.length - 1]} onChange={setEvalK}
          options={evalMeta.ks.map((k) => ({ value: k, label: `@${k}` }))} />
      </Space>
    ) : category === 'ad-report' ? (
      <Space size={8} wrap>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>指标</Typography.Text>
        <Select size="small" style={{ width: 130 }} value={adMetric} onChange={setAdMetric}
          options={adMetrics.map((m) => ({ value: m.v, label: m.v }))} />
      </Space>
    ) : null;

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }} align="center" wrap>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          共 {sorted.length} 份 · {xs[0]} → {xs[xs.length - 1]}
        </Typography.Text>
        {controls}
      </Space>
      <ELine categories={xs} series={built.series} yName={built.yName} fileName={`trend-${category}.png`} height={380} />
    </Space>
  );
}
