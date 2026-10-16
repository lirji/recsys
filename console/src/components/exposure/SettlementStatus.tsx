import type { ActionMode } from '../../api/sandboxActions';
import { ActionState } from '../../api/sandboxActions';
import { Button, Space, Tag, Typography } from 'antd';
import { useActionSettlement } from '../../hooks/useActionSettlement';
const labels: Record<string, string> = {
  WAITING_FOR_BOTH: '等待可见与点击',
  WAITING_FOR_VISIBLE: '等待有效可见',
  WAITING_FOR_CLICK: '等待点击',
  WAITING_FOR_CONVERSION: '等待业务转化',
  CAPTURED: '已结算',
  RELEASED: '已释放',
  EXPIRED: '已过期',
  REJECTED: '准备被拒绝',
};
export default function SettlementStatus({
  childId,
  mode = 'click',
}: {
  childId: string;
  mode?: ActionMode;
}) {
  const { settlement: s, busy, error, refresh } = useActionSettlement(childId, mode);
  return (
    <Space direction="vertical" size={4}>
      <Tag color={s?.state === ActionState.CAPTURED ? 'success' : undefined}>
        {s
          ? s.progress === 'WAITING_FOR_BOTH' && mode === 'conversion'
            ? '等待可见与业务转化'
            : labels[s.progress]
          : '结算尚未查询'}
      </Tag>
      <Typography.Text>
        {s?.state === ActionState.CAPTURED ? `资格实收 ¥${s.chargedAmount}` : '实收尚未确认'}
      </Typography.Text>
      {s && <Typography.Text type="secondary">预算日 {s.budgetDate}</Typography.Text>}
      {s?.ledgerId && (
        <Typography.Text style={{ overflowWrap: 'anywhere', fontSize: 12 }}>
          账本 {s.ledgerId}
        </Typography.Text>
      )}
      {error && <Typography.Text type="warning">{error}</Typography.Text>}
      <Button size="small" loading={busy} onClick={refresh}>
        刷新结算
      </Button>
    </Space>
  );
}
