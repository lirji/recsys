import { useEffect } from 'react';
import { Typography } from 'antd';
import { PreparationStatus, type SandboxAdItem } from '../../api/exposure';
import { useVisibleExposure, type MeasurementPhase } from '../../hooks/useVisibleExposure';
import { receiptQueue } from '../../exposure/receiptQueue';

export default function AdCreative({
  item,
  enabled,
  status,
}: {
  item: SandboxAdItem;
  enabled: boolean;
  status: (value: MeasurementPhase) => void;
}) {
  const preparation = item.preparation;
  const { ref, phase } = useVisibleExposure(
    enabled && !!item.title?.trim() && preparation?.status === PreparationStatus.QUALIFIED,
    preparation?.expiresAt ?? null,
    (duration, ratio) =>
      enabled &&
      !!preparation?.qualificationToken &&
      !!receiptQueue().enqueue(item.childId, preparation.expiresAt!, {
        qualificationToken: preparation.qualificationToken,
        visibleDurationMs: duration,
        minVisibleRatioBps: ratio,
        pageVisible: true,
      }),
  );
  useEffect(() => status(item.title?.trim() ? phase : 'unavailable'), [phase, status, item.title]);
  return (
    <div>
      <div
        ref={ref}
        data-testid={`creative-${item.slot}`}
        style={{
          minHeight: 84,
          padding: 16,
          border: '1px solid #d9e2f2',
          borderRadius: 8,
          background: '#fff',
        }}
      >
        <Typography.Text strong>{item.title || '创意内容缺失'}</Typography.Text>
      </div>
      <Typography.Text type="secondary" style={{ fontSize: 11 }}>
        广告 {item.adId} · 创意 {item.creativeId}
      </Typography.Text>
    </div>
  );
}
