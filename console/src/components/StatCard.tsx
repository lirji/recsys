import type { ReactNode } from 'react';
import { Card } from 'antd';
import { rgba } from '../theme/tokens';

// KPI 卡:顶部 accent 细条 + accent 图标砖 + 大号等宽数值 + 次级标题。复用漏斗/登录页已确立的图标砖视觉语言。
export default function StatCard({
  title,
  value,
  icon,
  accent,
  suffix,
  valueColor,
}: {
  title: ReactNode;
  value: ReactNode;
  icon?: ReactNode;
  accent: string;
  suffix?: ReactNode;
  valueColor?: string;
}) {
  return (
    <Card size="small" style={{ borderTop: `2px solid ${accent}` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        {icon ? (
          <span
            style={{
              width: 40,
              height: 40,
              borderRadius: 11,
              flex: '0 0 auto',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 18,
              color: accent,
              background: rgba(accent, 0.12),
              border: `1px solid ${rgba(accent, 0.28)}`,
            }}
          >
            {icon}
          </span>
        ) : null}
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 13, color: '#6b7280', lineHeight: 1.3 }}>{title}</div>
          <div
            className="mono"
            style={{ fontSize: 24, fontWeight: 800, lineHeight: 1.25, color: valueColor ?? '#1f2a44' }}
          >
            {value}
            {suffix ? (
              <span style={{ fontSize: 14, fontWeight: 600, marginLeft: 3, color: '#8a94a6' }}>{suffix}</span>
            ) : null}
          </div>
        </div>
      </div>
    </Card>
  );
}
