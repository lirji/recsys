import { useState, type ReactNode } from 'react';
import { Card } from 'antd';
import { DownOutlined } from '@ant-design/icons';

// 可折叠卡片:标题带强调色图标 + 左边框 accent,点击标题(或回车/空格)展开/收起。
// 复用全站玻璃卡片外观(.ant-card-bordered),只在标题加折叠交互,body 收起时 display:none(保留 header)。
export default function CollapsibleCard({
  title,
  icon,
  accent,
  extra,
  defaultOpen = true,
  children,
}: {
  title: ReactNode;
  icon?: ReactNode;
  accent?: string;
  extra?: ReactNode;
  defaultOpen?: boolean;
  children?: ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const toggle = () => setOpen((o) => !o);
  return (
    <Card
      style={accent ? { borderLeft: `3px solid ${accent}` } : undefined}
      styles={{ body: { display: open ? 'block' : 'none' } }}
      title={
        <span
          role="button"
          tabIndex={0}
          onClick={toggle}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              toggle();
            }
          }}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 8, cursor: 'pointer', userSelect: 'none' }}
        >
          {icon ? <span style={{ color: accent, display: 'inline-flex' }}>{icon}</span> : null}
          {title}
          <DownOutlined
            aria-hidden
            style={{
              fontSize: 11,
              color: '#8c8c8c',
              transition: 'transform .2s ease',
              transform: open ? 'none' : 'rotate(-90deg)',
            }}
          />
        </span>
      }
      extra={extra}
    >
      {children}
    </Card>
  );
}
