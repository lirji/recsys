import { Button, Space, Tag, Typography } from 'antd';
import { useSyncExternalStore } from 'react';
import { naturalReceiptQueue } from '../../exposure/naturalReceiptQueue';
import { ReceiptPhase } from '../../exposure/receiptQueueCore';
import type { MeasurementPhase } from '../../hooks/useVisibleExposure';

const labels: Record<string, string> = {
  pending: '待发送',
  sending: '确认中',
  unknown: '结果待确认',
  accepted: '曝光已接受',
  rejected: '未接受',
  blocked: '当前无法确认',
  expired: '未提交，资格已过期',
  waiting: '等待可见',
  timing: '可见计时中',
  measured: '等待回执',
  unavailable: '内容缺失，未采集',
  unsupported: '浏览器不支持可见测量',
  full: '回执队列已满',
  POLICY_NOT_MET: '未达可见政策',
  EXPIRED: '服务端已判定过期',
};
/** 自然回执确认的是曝光事实，不将接受误称为收费或 Kafka 已送达。 */
export default function NaturalReceiptStatus({
  qualificationId,
  measurement,
}: {
  qualificationId: string;
  measurement: MeasurementPhase;
}) {
  const queue = naturalReceiptQueue();
  const entry = useSyncExternalStore(queue.subscribe, () => queue.get(qualificationId));
  const accepted = entry?.phase === ReceiptPhase.ACCEPTED;
  return (
    <Space direction="vertical" size={4} style={{ maxWidth: '100%', overflowWrap: 'anywhere' }}>
      <Tag
        color={accepted ? 'success' : entry?.phase === ReceiptPhase.UNKNOWN ? 'warning' : undefined}
      >
        {entry?.phase === ReceiptPhase.REJECTED
          ? (labels[entry.result?.status ?? ''] ?? '未接受')
          : labels[entry?.phase ?? measurement]}
      </Tag>
      {accepted && (
        <Typography.Text type="secondary">接受时间 {entry.result?.acceptedAt}</Typography.Text>
      )}
      {entry?.receiptId && (
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          回执 {entry.receiptId}
        </Typography.Text>
      )}
      {accepted && entry.result?.canonicalReceiptId !== entry.receiptId && (
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          原回执 {entry.result?.canonicalReceiptId}
        </Typography.Text>
      )}
      {entry?.phase === ReceiptPhase.UNKNOWN && (
        <Button size="small" onClick={() => queue.query(qualificationId)}>
          查询原回执
        </Button>
      )}
    </Space>
  );
}
