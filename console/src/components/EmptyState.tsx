import type { ReactNode } from 'react';
import { BRAND, rgba } from '../theme/tokens';

// 品牌化空态:accent 淡底圆圈里放线性图标 + 标题 + 说明 + 可选下一步(按钮 / CLI 命令)。
// 替换全站参差的「纯文字」与默认灰盒 Empty,既好看又给出明确下一步、降低使用门槛。
export default function EmptyState({
  icon,
  title,
  description,
  action,
  accent = BRAND,
}: {
  icon?: ReactNode;
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  accent?: string;
}) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        textAlign: 'center',
        padding: '40px 16px',
        gap: 12,
      }}
    >
      <span
        style={{
          width: 56,
          height: 56,
          borderRadius: '50%',
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: 24,
          color: accent,
          background: rgba(accent, 0.1),
          border: `1px solid ${rgba(accent, 0.22)}`,
        }}
      >
        {icon}
      </span>
      <div style={{ fontSize: 15, fontWeight: 600, color: '#1f2a44' }}>{title}</div>
      {description ? (
        <div style={{ fontSize: 13, color: '#8a94a6', maxWidth: 440, lineHeight: 1.6 }}>{description}</div>
      ) : null}
      {action ? <div style={{ marginTop: 4 }}>{action}</div> : null}
    </div>
  );
}
