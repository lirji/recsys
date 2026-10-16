import { useSyncExternalStore } from 'react';
import { Alert, Button, Space, Tag, Typography } from 'antd';
import { ActionState } from '../../api/sandboxActions';
import { actionQueue, type ActionEntry } from '../../exposure/actionReceiptQueue';
const labels: Record<string, string> = {
  ACCEPTED_WAITING: '事件已接受，等待有效可见',
  ACCEPTED_CAPTURED: '事件已接受并触发结算',
  DUPLICATE_QUALIFICATION: '该资格已有业务事件，本事件不收费',
  OUTSIDE_ATTRIBUTION: '发生时间不在归因窗口内',
  EXPIRED: '资格已过期',
  NOT_HELD: '资格不可收费',
  pending: '待发送',
  sending: '正在确认原事件',
  unknown: '结果待确认',
  blocked: '操作被阻止',
  rejected: '事件未接受',
};
/** 事件自身实收与资格总实收分别显示；额外事件不能继承原事件的费用。 */
export default function ConversionOutcome({ entry }: { entry: ActionEntry }) {
  const queue = actionQueue(),
    o = entry.outcome;
  useSyncExternalStore(queue.subscribe, queue.snapshot);
  return (
    <Space direction="vertical" size={8} style={{ width: '100%', overflowWrap: 'anywhere' }}>
      <Typography.Text>业务事件 {entry.id}</Typography.Text>
      {entry.error && (
        <Alert
          type="warning"
          showIcon
          message={entry.error}
          description="当前查询未确认成功；下方如有结果，仅为上次服务端确认。请使用原事件查询。"
        />
      )}
      <Tag>{labels[o?.status ?? entry.phase]}</Tag>
      {o ? (
        <>
          <Typography.Text>发生时间 {o.occurredAt}</Typography.Text>
          <Typography.Text strong>
            {o.chargedForThisOutcome ? `本事件实收 ¥${o.chargedAmount}` : '本事件当前未收费'}
          </Typography.Text>
          <Typography.Text>
            {o.settlement.state === ActionState.CAPTURED
              ? `资格总实收 ¥${o.settlement.chargedAmount}`
              : '资格结算尚未完成'}
          </Typography.Text>
          <Typography.Text type="secondary">
            预算日 {o.settlement.budgetDate} · 资格 {o.qualificationId}
          </Typography.Text>
          {o.settlement.ledgerId && (
            <Typography.Text>资格账本 {o.settlement.ledgerId}</Typography.Text>
          )}
        </>
      ) : (
        <Typography.Text type="secondary">实收尚未确认；未知结果不能视为零元。</Typography.Text>
      )}
      <Button
        aria-label="查询原业务事件"
        aria-busy={queue.busy(entry.key)}
        size="small"
        loading={queue.busy(entry.key)}
        onClick={() => queue.query(entry.key)}
      >
        查询原业务事件
      </Button>
    </Space>
  );
}
