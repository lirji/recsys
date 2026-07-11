import type { ReactNode } from 'react';
import { Table, Tag, Typography } from 'antd';

// 通用 A/B 结果对比:两次请求的列表按 key 对齐,展示名次升降 / 新增·掉出 / 各指标 Δ。
// 推荐:key=itemId,指标=分数;广告:key=adId,指标=eCPM/实收。名次取列表位序(已排序)。

export interface DiffColumn<T> {
  label: string;
  get: (t: T) => number;
  fmt?: (n: number) => string;
}

interface Row {
  key: string;
  name: ReactNode;
  rankA: number | null;
  rankB: number | null;
  vals: { a: number | null; b: number | null }[];
}

export default function ResultDiff<T>({
  aLabel,
  bLabel,
  aItems,
  bItems,
  keyOf,
  primary,
  columns,
  topK = 10,
}: {
  aLabel: string;
  bLabel: string;
  aItems: T[];
  bItems: T[];
  keyOf: (t: T) => number | string;
  primary: (t: T) => ReactNode;
  columns: DiffColumn<T>[];
  topK?: number;
}) {
  const mapA = new Map<string, { t: T; rank: number }>();
  aItems.forEach((t, i) => mapA.set(String(keyOf(t)), { t, rank: i + 1 }));
  const mapB = new Map<string, { t: T; rank: number }>();
  bItems.forEach((t, i) => mapB.set(String(keyOf(t)), { t, rank: i + 1 }));

  const keys = Array.from(new Set([...mapA.keys(), ...mapB.keys()]));
  const rows: Row[] = keys.map((k) => {
    const a = mapA.get(k);
    const b = mapB.get(k);
    const src = (b?.t ?? a?.t) as T;
    return {
      key: k,
      name: primary(src),
      rankA: a?.rank ?? null,
      rankB: b?.rank ?? null,
      vals: columns.map((c) => ({ a: a ? c.get(a.t) : null, b: b ? c.get(b.t) : null })),
    };
  });
  rows.sort((x, y) => (x.rankB ?? 1e9) - (y.rankB ?? 1e9) || (x.rankA ?? 1e9) - (y.rankA ?? 1e9));

  const topKeysA = new Set(aItems.slice(0, topK).map((t) => String(keyOf(t))));
  const topKeysB = new Set(bItems.slice(0, topK).map((t) => String(keyOf(t))));
  let overlap = 0;
  topKeysB.forEach((k) => {
    if (topKeysA.has(k)) overlap += 1;
  });
  const onlyB = keys.filter((k) => mapB.has(k) && !mapA.has(k)).length; // 新增
  const onlyA = keys.filter((k) => mapA.has(k) && !mapB.has(k)).length; // 掉出

  const fmt = (c: DiffColumn<T>, n: number | null) => (n == null ? '—' : c.fmt ? c.fmt(n) : String(n));

  const rankCell = (rankA: number | null, rankB: number | null) => {
    if (rankA == null) return <Tag color="blue">新增 #{rankB}</Tag>;
    if (rankB == null) return <Tag>掉出(原 #{rankA})</Tag>;
    const d = rankA - rankB; // 正=名次上升
    const color = d > 0 ? '#389e0d' : d < 0 ? '#cf1322' : '#8a94a6';
    const arrow = d > 0 ? '↑' : d < 0 ? '↓' : '=';
    return (
      <span className="mono">
        #{rankA} → #{rankB}{' '}
        <span style={{ color }}>
          {arrow}
          {d !== 0 ? Math.abs(d) : ''}
        </span>
      </span>
    );
  };

  const antdColumns = [
    { title: '名次变化', key: 'rank', width: 168, render: (_: unknown, r: Row) => rankCell(r.rankA, r.rankB) },
    { title: '对象', key: 'name', render: (_: unknown, r: Row) => r.name },
    ...columns.map((c, ci) => ({
      title: c.label,
      key: `c${ci}`,
      width: 168,
      render: (_: unknown, r: Row) => {
        const v = r.vals[ci];
        const delta = v.a != null && v.b != null ? v.b - v.a : null;
        const dcolor = delta == null || delta === 0 ? '#8a94a6' : delta > 0 ? '#389e0d' : '#cf1322';
        return (
          <span className="mono" style={{ fontSize: 12 }}>
            {fmt(c, v.a)} → {fmt(c, v.b)}
            {delta != null && delta !== 0 ? (
              <span style={{ color: dcolor }}>
                {' '}
                ({delta > 0 ? '+' : ''}
                {c.fmt ? c.fmt(delta) : delta})
              </span>
            ) : null}
          </span>
        );
      },
    })),
  ];

  return (
    <div>
      <div style={{ marginBottom: 12, display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          A = {aLabel}
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          B = {bLabel}
        </Typography.Text>
        <Tag color="geekblue">
          top-{topK} 重合 {overlap}
        </Tag>
        <Tag color="blue">新增 {onlyB}</Tag>
        <Tag>掉出 {onlyA}</Tag>
      </div>
      <Table<Row>
        size="small"
        rowKey="key"
        columns={antdColumns}
        dataSource={rows}
        pagination={rows.length > 20 ? { pageSize: 20 } : false}
        scroll={{ x: 640 }}
      />
    </div>
  );
}
