import { ActionState, PreparationStatus } from '../../api/sandboxActions';
import { useSyncExternalStore } from 'react';
import { Button, Space, Typography } from 'antd';
import type { ActionAdItem } from '../../api/sandboxActions';
import { actionKey, actionQueue } from '../../exposure/actionReceiptQueue';
import { useVisibleExposure } from '../../hooks/useVisibleExposure';
import { useExposureScope } from './ExposureScope';

const phases: Record<string, string> = {
  waiting: '等待真实可见',
  timing: '可见计时中',
  measured: '已取得有效可见观测',
  expired: '本地发送期限已到，请查询原结果',
  unsupported: '浏览器不支持可见观测',
  full: '队列已满或身份已失效',
  unavailable: '创意不可用',
};
/** 新模式独立发送，不能让固定CPM回执组件消费点击票据。 */
export default function ActionAdCreative({
  item,
  enabled,
}: {
  item: ActionAdItem;
  enabled: boolean;
}) {
  const identity = useExposureScope(),
    queue = actionQueue(),
    p = item.preparation;
  useSyncExternalStore(queue.subscribe, queue.snapshot);
  const eligible =
    !!identity &&
    enabled &&
    p?.status === PreparationStatus.QUALIFIED &&
    !!p.qualificationToken &&
    !!item.title.trim();
  const { ref, phase } = useVisibleExposure(
    eligible,
    p?.expiresAt ?? null,
    (duration, ratio) =>
      !!(
        eligible &&
        p?.qualificationToken &&
        p.expiresAt &&
        queue.enqueue(item.childId, p.expiresAt, {
          kind: 'VISIBLE',
          qualificationToken: p.qualificationToken,
          visibleDurationMs: duration,
          minVisibleRatioBps: ratio,
          pageVisible: true,
        })
      ),
  );
  const clicked = queue.get(actionKey(item.childId, 'CLICK'));
  const closed = queue.settlement(item.childId)?.state;
  const expired = !p?.expiresAt || Date.now() >= Date.parse(p.expiresAt);
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <div
        ref={ref}
        data-testid={`action-creative-${item.slot}`}
        style={{
          minHeight: 84,
          padding: 16,
          border: '1px solid var(--ant-color-border, #d9e2f2)',
          borderRadius: 8,
        }}
      >
        <Typography.Text strong>{item.title || '创意内容缺失'}</Typography.Text>
      </div>
      <Typography.Text type="secondary" style={{ overflowWrap: 'anywhere', fontSize: 12 }}>
        广告 {item.adId} · 创意 {item.creativeId}
      </Typography.Text>
      <Typography.Text type="secondary">
        {enabled ? phases[phase] : '只读结果，观测已暂停'}
      </Typography.Text>
      <Button
        disabled={!eligible || !!clicked || expired || (!!closed && closed !== ActionState.HELD)}
        onClick={() =>
          p?.qualificationToken &&
          p.expiresAt &&
          queue.enqueue(item.childId, p.expiresAt, {
            kind: 'CLICK',
            qualificationToken: p.qualificationToken,
            visibleDurationMs: 0,
            minVisibleRatioBps: 0,
            pageVisible: false,
          })
        }
      >
        {clicked ? '点击已记录' : '模拟点击'}
      </Button>
    </Space>
  );
}
