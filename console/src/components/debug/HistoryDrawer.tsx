import type { ReactNode } from 'react';
import { Button, Drawer, List, Space, Tag, Typography } from 'antd';
import EmptyState from '../EmptyState';
import { HistoryOutlined } from '@ant-design/icons';
import { ACCENTS } from '../../theme/tokens';
import type { HistoryEntry } from '../../hooks/useRequestHistory';

function relTime(ts: number): string {
  const s = Math.floor((Date.now() - ts) / 1000);
  if (s < 5) return '刚刚';
  if (s < 60) return `${s}s 前`;
  if (s < 3600) return `${Math.floor(s / 60)}m 前`;
  return new Date(ts).toLocaleTimeString();
}

// 通用请求历史抽屉:列出最近请求,可「重跑」某条、勾选两条「对比」。摘要与重跑逻辑由各页传入。
export default function HistoryDrawer<P, D>({
  open,
  onClose,
  entries,
  selected,
  onToggleSelect,
  onRerun,
  onCompare,
  onClear,
  renderSummary,
}: {
  open: boolean;
  onClose: () => void;
  entries: HistoryEntry<P, D>[];
  selected: string[];
  onToggleSelect: (id: string) => void;
  onRerun: (e: HistoryEntry<P, D>) => void;
  onCompare: () => void;
  onClear: () => void;
  renderSummary: (e: HistoryEntry<P, D>) => ReactNode;
}) {
  return (
    <Drawer
      title="请求历史"
      open={open}
      onClose={onClose}
      width={440}
      extra={
        <Button size="small" onClick={onClear} disabled={!entries.length}>
          清空
        </Button>
      }
      footer={
        <Button type="primary" block disabled={selected.length !== 2} onClick={onCompare}>
          对比选中的两条{selected.length ? ` (${selected.length}/2)` : ''}
        </Button>
      }
    >
      {entries.length === 0 ? (
        <EmptyState
          icon={<HistoryOutlined />}
          accent={ACCENTS.recall}
          title="还没有请求"
          description="跑一次就会记在这里,可重跑或选两条做 A/B 对比。"
        />
      ) : (
        <List
          dataSource={entries}
          split={false}
          renderItem={(e) => {
            const sel = selected.includes(e.id);
            return (
              <List.Item
                style={{
                  border: `1px solid ${sel ? '#91caff' : '#eef1f7'}`,
                  background: sel ? 'rgba(45,108,223,0.05)' : '#fff',
                  borderRadius: 10,
                  padding: 12,
                  marginBottom: 8,
                  display: 'block',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                  <Space size={8}>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {relTime(e.ts)}
                    </Typography.Text>
                    <Tag className="mono">{e.durationMs}ms</Tag>
                  </Space>
                  <Space size={4}>
                    <Button size="small" onClick={() => onRerun(e)}>
                      重跑
                    </Button>
                    <Button size="small" type={sel ? 'primary' : 'default'} onClick={() => onToggleSelect(e.id)}>
                      {sel ? '已选' : '对比'}
                    </Button>
                  </Space>
                </div>
                <div style={{ marginTop: 6, fontSize: 13 }}>{renderSummary(e)}</div>
              </List.Item>
            );
          }}
        />
      )}
    </Drawer>
  );
}
