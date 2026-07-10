// 分数条:把 score 相对列表最大值画成一条进度条,右侧显示原始数值(等宽)。
export default function ScoreBar({ value, max }: { value: number; max: number }) {
  const pct = max > 0 ? Math.max(2, Math.min(100, Math.round((value / max) * 100))) : 0;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 180 }}>
      <div style={{ flex: 1, height: 6, background: '#f0f0f0', borderRadius: 3, overflow: 'hidden' }}>
        <div style={{ width: `${pct}%`, height: '100%', background: '#2d6cdf' }} />
      </div>
      <span className="mono" style={{ fontSize: 12, color: '#666', minWidth: 64, textAlign: 'right' }}>
        {Number.isFinite(value) ? value.toFixed(4) : '—'}
      </span>
    </div>
  );
}
