import type { ReactNode } from 'react';

// 轻量页头:标题 + 一行描述 + 右侧操作区(可选 accent 左条)。给每个页面稳定的信息层级 ——
// 在线各调试台此前无任何标题,进页直接是控制条,靠侧栏才知身在何处。
export default function PageHeader({
  title,
  description,
  extra,
  accent,
}: {
  title: ReactNode;
  description?: ReactNode;
  extra?: ReactNode;
  accent?: string;
}) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'space-between',
        gap: 16,
        flexWrap: 'wrap',
        ...(accent ? { borderLeft: `3px solid ${accent}`, paddingLeft: 12 } : {}),
      }}
    >
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 20, fontWeight: 700, color: '#1f2a44', letterSpacing: '.2px' }}>{title}</div>
        {description ? (
          <div style={{ fontSize: 13, color: '#8a94a6', marginTop: 4, lineHeight: 1.6, maxWidth: 760 }}>
            {description}
          </div>
        ) : null}
      </div>
      {extra ? <div style={{ flex: '0 0 auto' }}>{extra}</div> : null}
    </div>
  );
}
