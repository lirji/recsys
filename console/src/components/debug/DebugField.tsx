import type { ReactNode } from 'react';

/** 调试台控制条字段:短中文标签 + 控件,替代裸字母 q/size。 */
export default function DebugField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="dbg-field">
      <span className="dbg-field-label">{label}</span>
      {children}
    </label>
  );
}
