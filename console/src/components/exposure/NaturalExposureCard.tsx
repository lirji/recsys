import { Card, Space, Tag, Typography } from 'antd';
import type { NaturalItem } from '../../api/naturalExposure';
import { useVisibleExposure } from '../../hooks/useVisibleExposure';
import { naturalReceiptQueue } from '../../exposure/naturalReceiptQueue';
import NaturalReceiptStatus from './NaturalReceiptStatus';
import RecallTags from '../explain/RecallTags';

/** 只渲染本次冻结正文；不从当前表单或随后变化的详情接口补写资格上下文。 */
export default function NaturalExposureCard({
  item,
  enabled,
}: {
  item: NaturalItem;
  enabled: boolean;
}) {
  const { ref, phase } = useVisibleExposure(
    enabled,
    item.expiresAt,
    (duration, ratio) =>
      enabled &&
      !!naturalReceiptQueue().enqueue(item.qualificationId, item.expiresAt, {
        qualificationToken: item.qualificationToken,
        visibleDurationMs: duration,
        minVisibleRatioBps: ratio,
        pageVisible: true,
      }),
  );
  return (
    <Card size="small" data-testid={`natural-item-${item.slot}`}>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
        <div style={{ flex: '1 1 360px', minWidth: 0, overflowWrap: 'anywhere' }}>
          <div
            ref={ref}
            data-testid={`natural-content-${item.slot}`}
            style={{ minHeight: 84, padding: 12, border: '1px solid #d9e2f2', borderRadius: 8 }}
          >
            <Typography.Text strong>{item.title}</Typography.Text>
            {item.category && (
              <div style={{ marginTop: 4 }}>
                <Tag style={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>
                  {item.category}
                </Tag>
              </div>
            )}
            {item.description && (
              <Typography.Paragraph
                style={{ marginTop: 8, marginBottom: 0, whiteSpace: 'pre-wrap' }}
              >
                {item.description}
              </Typography.Paragraph>
            )}
          </div>
          <Space wrap size={8} style={{ marginTop: 8 }}>
            <Typography.Text type="secondary">
              位置 {item.slot} · 内容 {item.itemId}
            </Typography.Text>
            <RecallTags channels={item.recallFrom} />
            <Typography.Text type="secondary">分数 {item.score.toFixed(4)}</Typography.Text>
          </Space>
          {item.reason && (
            <div>
              <Typography.Text type="secondary">{item.reason}</Typography.Text>
            </div>
          )}
        </div>
        <div style={{ flex: '0 1 280px', minWidth: 0 }}>
          <NaturalReceiptStatus qualificationId={item.qualificationId} measurement={phase} />
        </div>
      </div>
    </Card>
  );
}
