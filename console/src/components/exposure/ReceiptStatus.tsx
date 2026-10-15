import { Button, Space, Tag, Typography } from 'antd';
import { useSyncExternalStore } from 'react';
import { ReceiptPhase, receiptQueue } from '../../exposure/receiptQueue';
import type { MeasurementPhase } from '../../hooks/useVisibleExposure';

const labels: Record<string, string> = {
  pending: '待发送',
  sending: '确认中',
  unknown: '结果待确认',
  accepted: '已接受',
  rejected: '未接受',
  blocked: '当前无法确认',
  expired: '未提交，资格已过期',
  waiting: '等待可见',
  timing: '可见计时中',
  unavailable: '创意内容缺失，未采集',
  measured: '等待回执',
  unsupported: '浏览器不支持可见测量',
  full: '回执队列已满',
  POLICY_NOT_MET: '未达可见政策',
  EXPIRED: '服务端已判定过期',
  NOT_HELD: '预占不可用',
};
/** 只显示服务端已提交结果的金额；超时/404 不显示零消费。 */
export default function ReceiptStatus({
  childId,
  measurement,
}: {
  childId: string;
  measurement: MeasurementPhase;
}) {
  const queue = receiptQueue();
  const entry = useSyncExternalStore(queue.subscribe, () => queue.get(childId));
  const accepted = entry?.phase === ReceiptPhase.ACCEPTED;
  return (
    <Space direction="vertical" size={4}>
      <Tag
        color={accepted ? 'success' : entry?.phase === ReceiptPhase.UNKNOWN ? 'warning' : undefined}
      >
        {entry?.phase === ReceiptPhase.REJECTED
          ? (labels[entry.result?.status ?? ''] ?? '未接受')
          : labels[entry?.phase ?? measurement]}
      </Tag>
      <Typography.Text className="mono">
        {accepted ? `已确认消费 ¥${entry.result?.chargedAmount}` : '消费尚未确认'}
      </Typography.Text>
      {entry?.receiptId && (
        <Typography.Text type="secondary" style={{ fontSize: 11, overflowWrap: 'anywhere' }}>
          回执 {entry.receiptId}
        </Typography.Text>
      )}
      {entry?.phase === ReceiptPhase.UNKNOWN && (
        <Button size="small" onClick={() => queue.query(childId)}>
          查询原回执
        </Button>
      )}
    </Space>
  );
}
