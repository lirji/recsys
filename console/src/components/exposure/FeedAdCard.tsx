import { useState } from 'react';
import { Card, Space, Tag, Typography } from 'antd';
import { PreparationStatus, type SandboxAdItem } from '../../api/exposure';
import type { MeasurementPhase } from '../../hooks/useVisibleExposure';
import AdCreative from './AdCreative';
import ReceiptStatus from './ReceiptStatus';

const reasons = {
  [PreparationStatus.INSUFFICIENT_BUDGET]: '预算不足，本位置未投放',
  [PreparationStatus.OFFER_EXPIRED]: '候选已过期，本位置未投放',
  [PreparationStatus.QUOTE_EXPIRED]: '报价已过期，本位置未投放',
  [PreparationStatus.ACCOUNT_MISSING]: '测试预算账户不存在，本位置未投放',
} as const;
/** 未知/拒绝只显示占位；创意区域与回执始终绑定同一原子操作和最终位置。 */
export default function FeedAdCard({ item, enabled }: { item: SandboxAdItem; enabled: boolean }) {
  const [phase, setPhase] = useState<MeasurementPhase>('waiting');
  const preparation = item.preparation;
  const qualified = preparation?.status === PreparationStatus.QUALIFIED;
  return (
    <Card size="small" data-testid={`feed-ad-${item.slot}`}>
      <Space wrap style={{ marginBottom: 8 }}>
        <Tag color="gold">赞助</Tag>
        <Typography.Text type="secondary">位置 {item.slot}</Typography.Text>
      </Space>
      {qualified ? (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
          <div style={{ flex: '1 1 360px', minWidth: 0, overflowWrap: 'anywhere' }}>
            <AdCreative item={item} enabled={enabled} status={setPhase} />
            <Typography.Text type="secondary">
              预占报价 ¥{preparation.amount} / 次曝光
            </Typography.Text>
          </div>
          <div style={{ flex: '0 1 280px', minWidth: 0 }}>
            <ReceiptStatus childId={item.childId} measurement={phase} />
          </div>
        </div>
      ) : (
        <div style={{ minHeight: 84, padding: 12, background: '#fafafa', borderRadius: 8 }}>
          <Typography.Text strong>
            {preparation ? reasons[preparation.status as keyof typeof reasons] : '广告准备结果未知'}
          </Typography.Text>
          <div style={{ marginTop: 8 }}>
            <Typography.Text type="secondary">
              {preparation
                ? '保留最终位置，可继续浏览自然内容。'
                : '请恢复原操作确认结果；此占位不采集曝光。'}
            </Typography.Text>
          </div>
        </div>
      )}
    </Card>
  );
}
