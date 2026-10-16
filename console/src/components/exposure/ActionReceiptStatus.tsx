import { ActionKinds } from '../../api/sandboxActions';
import { useSyncExternalStore } from 'react';
import { Button, Space, Tag, Typography } from 'antd';
import { actionKey, actionQueue } from '../../exposure/actionReceiptQueue';
import type { ActionKind, ActionMode } from '../../api/sandboxActions';

const labels: Record<string, string> = {
  pending: '待发送',
  sending: '确认中',
  unknown: '结果待确认',
  accepted: '条件已接受',
  rejected: '条件未接受',
  blocked: '操作已阻止',
  ACCEPTED_WAITING: '已接受，等待另一条件',
  ACCEPTED_CAPTURED: '已接受并触发结算',
  ALREADY_ACCEPTED: '原条件已接受',
  POLICY_NOT_MET: '可见条件未满足',
  FRAUD_REJECTED: '风控拒绝',
  EXPIRED: '资格已过期',
  NOT_HELD: '资格不可收费',
};
export default function ActionReceiptStatus({
  childId,
  kind,
  mode = 'click',
}: {
  childId: string;
  kind: ActionKind;
  mode?: ActionMode;
}) {
  const queue = actionQueue(),
    key = actionKey(childId, kind, mode);
  useSyncExternalStore(queue.subscribe, queue.snapshot);
  const entry = queue.get(key);
  return (
    <Space direction="vertical" size={4}>
      <Typography.Text>{kind === ActionKinds.CLICK ? '点击条件' : '可见条件'}</Typography.Text>
      <Tag>{entry ? labels[entry.result?.receipt.status ?? entry.phase] : '尚未提交'}</Tag>
      {entry && (
        <>
          <Typography.Text type="secondary" style={{ overflowWrap: 'anywhere', fontSize: 12 }}>
            回执 {entry.id}
          </Typography.Text>
          {entry.error && <Typography.Text type="warning">{entry.error}</Typography.Text>}
          <Button size="small" loading={queue.busy(key)} onClick={() => queue.query(key)}>
            查询原{kind === ActionKinds.CLICK ? '点击' : '可见'}回执
          </Button>
        </>
      )}
    </Space>
  );
}
