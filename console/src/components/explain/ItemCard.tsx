import type { ReactNode } from 'react';
import { Space, Tag, Typography } from 'antd';
import type { ItemMeta, RecommendItem, ScoreBreakdown } from '../../api/types';
import RecallTags from './RecallTags';
import ScoreBar from './ScoreBar';
import ScoreBreakdownPopover from './ScoreBreakdownPopover';
import { channelColor } from './channelColors';
import { ACCENTS, BRAND, hexOfPreset, rgba } from '../../theme/tokens';

// 单条推荐结果卡片:序号徽章 + itemId + 召回来源标签 + 推荐理由 + 分数条 + 右侧操作区。
// 左边框 accent = 首个召回通道色(一眼识别主召回路);前三名用渐变发光徽章。hover 微抬升见 index.css 的 .itc-row。
export default function ItemCard({
  item,
  rank,
  maxScore,
  actions,
  meta,
  breakdown,
}: {
  item: RecommendItem;
  rank: number;
  maxScore: number;
  actions?: ReactNode;
  meta?: ItemMeta;
  breakdown?: ScoreBreakdown;
}) {
  const accent = item.recallFrom.length ? hexOfPreset(channelColor(item.recallFrom[0])) : BRAND;
  const top3 = rank <= 3;
  return (
    <div className="itc-row" style={{ borderLeft: `3px solid ${accent}` }}>
      <div
        className="itc-rank"
        style={
          top3
            ? {
                color: '#fff',
                background: `linear-gradient(135deg, ${accent}, ${ACCENTS.rerank})`,
                boxShadow: `0 0 12px -2px ${rgba(accent, 0.6)}`,
              }
            : { color: '#8a94a6', background: '#f2f4f8' }
        }
      >
        {rank}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <Space size={8} wrap>
          {meta?.title ? (
            <>
              <Typography.Text strong ellipsis style={{ maxWidth: 260 }}>
                {meta.title}
              </Typography.Text>
              <Typography.Text type="secondary" className="mono" style={{ fontSize: 12 }}>
                #{item.itemId}
              </Typography.Text>
              {meta.category ? <Tag color="blue">{meta.category}</Tag> : null}
            </>
          ) : (
            <Typography.Text strong className="mono">
              #{item.itemId}
            </Typography.Text>
          )}
          <RecallTags channels={item.recallFrom} />
        </Space>
        {item.reason ? (
          <div style={{ color: '#666', fontSize: 13, marginTop: 2 }}>{item.reason}</div>
        ) : null}
      </div>
      <ScoreBar value={item.score} max={maxScore} accent={accent} />
      {breakdown ? <ScoreBreakdownPopover breakdown={breakdown} /> : null}
      {actions ? <div style={{ display: 'flex', gap: 6 }}>{actions}</div> : null}
    </div>
  );
}
