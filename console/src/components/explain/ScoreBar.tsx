import { ACCENTS, BRAND, rgba } from '../../theme/tokens';

// 分数条:把 score 相对列表最大值画成一条渐变发光进度条,右侧显示原始数值(等宽)。
// accent 缺省 = 品牌蓝;由 ItemCard 传入首个召回通道色,让分数条与该行左边框/标签同色。
// 结构 + 过渡样式在 index.css 的 .scb-*(含 reduced-motion 守卫),这里只给动态色/宽度。
export default function ScoreBar({ value, max, accent = BRAND }: { value: number; max: number; accent?: string }) {
  const pct = max > 0 ? Math.max(2, Math.min(100, Math.round((value / max) * 100))) : 0;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 180 }}>
      <div className="scb-track" style={{ background: rgba(BRAND, 0.08) }}>
        <div
          className="scb-fill"
          style={{
            width: `${pct}%`,
            background: `linear-gradient(90deg, ${rgba(accent, 0.95)}, ${rgba(ACCENTS.rank, 0.9)})`,
            boxShadow: `0 0 8px ${rgba(accent, 0.45)}`,
          }}
        />
      </div>
      <span className="mono" style={{ fontSize: 12, color: '#666', minWidth: 64, textAlign: 'right' }}>
        {Number.isFinite(value) ? value.toFixed(4) : '—'}
      </span>
    </div>
  );
}
