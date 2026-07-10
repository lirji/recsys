import { Tag } from 'antd';

// 广告 / 广告主状态 → 颜色标签的统一渲染。
export function StatusTag({ status }: { status?: string }) {
  const s = (status ?? '').toLowerCase();
  const color =
    s === 'active' ? 'green' : s === 'paused' ? 'orange' : s === 'rejected' ? 'red' : s.includes('pending') ? 'gold' : 'default';
  return <Tag color={color}>{status ?? '—'}</Tag>;
}
