import type { ReactNode } from 'react';
import { Space, Typography } from 'antd';
import type { RecommendItem } from '../../api/types';
import RecallTags from './RecallTags';
import ScoreBar from './ScoreBar';

// 单条推荐结果卡片:序号 + itemId + 召回来源标签 + 推荐理由 + 分数条 + 右侧操作区(如上报点击)。
export default function ItemCard({
  item,
  rank,
  maxScore,
  actions,
}: {
  item: RecommendItem;
  rank: number;
  maxScore: number;
  actions?: ReactNode;
}) {
  return (
    <div
      style={{
        border: '1px solid #eee',
        borderRadius: 10,
        padding: '10px 14px',
        display: 'flex',
        alignItems: 'center',
        gap: 14,
      }}
    >
      <div style={{ width: 28, textAlign: 'center', color: '#bbb', fontWeight: 700 }}>{rank}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <Space size={8} wrap>
          <Typography.Text strong className="mono">
            #{item.itemId}
          </Typography.Text>
          <RecallTags channels={item.recallFrom} />
        </Space>
        {item.reason ? (
          <div style={{ color: '#666', fontSize: 13, marginTop: 2 }}>{item.reason}</div>
        ) : null}
      </div>
      <ScoreBar value={item.score} max={maxScore} />
      {actions ? <div style={{ display: 'flex', gap: 6 }}>{actions}</div> : null}
    </div>
  );
}
