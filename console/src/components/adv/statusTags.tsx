import { Tag } from 'antd';

// 广告 / 广告主状态 → 颜色标签的统一渲染。
export function StatusTag({ status }: { status?: string }) {
  const s = (status ?? '').toLowerCase();
  const color =
    s === 'active'
      ? 'green'
      : s === 'paused'
        ? 'orange'
        : s === 'rejected'
          ? 'red'
          : s.includes('pending')
            ? 'gold'
            : 'default';
  return <Tag color={color}>{status ?? '—'}</Tag>;
}

/** 审核是独立事实，旧服务缺字段或未知枚举不能当作已通过。 */
export function ReviewStatusTag({ status }: { status?: string | null }) {
  switch (status) {
    case 'approved':
      return <Tag color="green">审核通过</Tag>;
    case 'pending_review':
      return <Tag color="gold">待审核</Tag>;
    case 'rejected':
      return <Tag color="red">已驳回</Tag>;
    default:
      return <Tag>审核状态未知</Tag>;
  }
}
