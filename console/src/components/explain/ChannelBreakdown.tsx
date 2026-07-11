import { Space, Table, Tag, Typography } from 'antd';
import { PartitionOutlined } from '@ant-design/icons';
import type { RecommendExplain, RecommendItem } from '../../api/types';
import { channelColor } from './channelColors';
import EBar from '../charts/EBar';
import EmptyState from '../EmptyState';
import { ACCENTS, BRAND, hexOfPreset, rgba } from '../../theme/tokens';

// 逐召回通道的三级计数:原始召回 → 去重贡献 → 最终存活。
export interface ChannelStat {
  channel: string;
  rawCount: number; // 去重前该路原始召回条数(来自 explain.channelRecall)
  contribution: number; // 去重后对候选池的贡献条数(来自 explain.channelContribution)
  survived: number; // 最终结果里由该路命中的 item 数(数 final items 的 recallFrom)
}

/**
 * 纯函数:把 explain 的逐路计数 + final items 的 recallFrom 归约成逐通道统计。
 * - rawCount 取 channelRecall、contribution 取 channelContribution、survived 数 final items 中 recallFrom 含该路者。
 * - 通道全集 = 三者并集(某路可能召回了但一条都没存活,仍要出现在表里)。
 * - 排序:rawCount 降序,其次 survived 降序,再按通道名稳定排序。
 * 诚实口径:explain 只给逐路计数,不给各路完整原始 item 列表 —— 故未存活明细无法逐条还原,不虚构。
 */
export function deriveChannelStats(
  explain: RecommendExplain | null | undefined,
  items: RecommendItem[],
): ChannelStat[] {
  const raw = new Map<string, number>();
  const contrib = new Map<string, number>();
  const survived = new Map<string, number>();
  explain?.channelRecall?.forEach((c) => raw.set(c.channel, c.rawCount));
  explain?.channelContribution?.forEach((c) => contrib.set(c.channel, c.count));
  items.forEach((it) => {
    new Set(it.recallFrom).forEach((ch) => survived.set(ch, (survived.get(ch) ?? 0) + 1));
  });
  const channels = new Set<string>([...raw.keys(), ...contrib.keys(), ...survived.keys()]);
  return Array.from(channels)
    .map((channel) => ({
      channel,
      rawCount: raw.get(channel) ?? 0,
      contribution: contrib.get(channel) ?? 0,
      survived: survived.get(channel) ?? 0,
    }))
    .sort(
      (a, b) =>
        b.rawCount - a.rawCount ||
        b.survived - a.survived ||
        a.channel.localeCompare(b.channel),
    );
}

// 召回通道分解:分组柱(原始/贡献/存活)+ 明细表(点行可过滤下方结果)。
export default function ChannelBreakdown({
  stats,
  selectedChannel,
  onSelectChannel,
}: {
  stats: ChannelStat[];
  selectedChannel: string | null;
  onSelectChannel: (ch: string | null) => void;
}) {
  if (stats.length === 0) {
    return (
      <EmptyState
        icon={<PartitionOutlined />}
        accent={ACCENTS.recall}
        title="暂无召回分解"
        description="需要 explain=true 的真实逐路计数,且已灌入召回存储(向量 / CF / 热门等)。"
      />
    );
  }

  const categories = stats.map((s) => s.channel);
  const series = [
    { name: '原始召回', data: stats.map((s) => s.rawCount) },
    { name: '去重贡献', data: stats.map((s) => s.contribution) },
    { name: '最终存活', data: stats.map((s) => s.survived) },
  ];

  // 三级计数统一右对齐 + 等宽,漏损逐级看得清。
  const numCell = (v: number, color: string) => (
    <span className="mono" style={{ color, fontWeight: 600 }}>
      {v}
    </span>
  );

  const columns = [
    {
      title: '通道',
      dataIndex: 'channel',
      key: 'channel',
      render: (ch: string) => <Tag color={channelColor(ch)}>{ch}</Tag>,
    },
    {
      title: '原始召回',
      dataIndex: 'rawCount',
      key: 'rawCount',
      width: 96,
      align: 'right' as const,
      render: (v: number) => numCell(v, '#1f2a44'),
    },
    {
      title: '去重贡献',
      dataIndex: 'contribution',
      key: 'contribution',
      width: 96,
      align: 'right' as const,
      render: (v: number) => numCell(v, '#4b5563'),
    },
    {
      title: '最终存活',
      dataIndex: 'survived',
      key: 'survived',
      width: 96,
      align: 'right' as const,
      render: (v: number, r: ChannelStat) =>
        numCell(v, v > 0 ? hexOfPreset(channelColor(r.channel)) : '#b0b7c3'),
    },
    {
      title: '存活率',
      key: 'rate',
      width: 148,
      align: 'right' as const,
      render: (_: unknown, r: ChannelStat) => {
        if (r.rawCount <= 0) return <Typography.Text type="secondary">—</Typography.Text>;
        const rate = (r.survived / r.rawCount) * 100;
        const color = rate >= 30 ? ACCENTS.gsp : rate >= 10 ? ACCENTS.ad : '#98a1b2';
        return (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8 }}>
            <span style={{ width: 60, height: 6, borderRadius: 3, background: '#eef1f6', overflow: 'hidden' }}>
              <span
                style={{
                  display: 'block',
                  width: `${Math.min(100, rate)}%`,
                  height: '100%',
                  borderRadius: 3,
                  background: color,
                }}
              />
            </span>
            <span className="mono" style={{ color, width: 46, textAlign: 'right' }}>
              {rate.toFixed(1)}%
            </span>
          </div>
        );
      },
    },
  ];

  return (
    <Space direction="vertical" size={14} style={{ width: '100%' }}>
      <EBar categories={categories} series={series} yName="条数" height={300} />
      <div
        style={{
          fontSize: 12,
          color: '#6b7280',
          lineHeight: 1.6,
          padding: '8px 12px',
          borderRadius: 8,
          background: rgba(BRAND, 0.05),
          border: `1px solid ${rgba(BRAND, 0.12)}`,
        }}
      >
        点通道行 → 下方结果按该路过滤;再点一次或点「全部」还原。
        <b> 原始召回 ≥ 去重贡献 ≥ 最终存活 </b>
        逐级漏损,存活率越高表示该路候选越贴合最终结果。
      </div>
      <Table<ChannelStat>
        size="small"
        rowKey="channel"
        columns={columns}
        dataSource={stats}
        pagination={false}
        scroll={{ x: 560 }}
        onRow={(record) => ({
          onClick: () => onSelectChannel(selectedChannel === record.channel ? null : record.channel),
          style: {
            cursor: 'pointer',
            background: selectedChannel === record.channel ? rgba(BRAND, 0.08) : undefined,
          },
        })}
      />
    </Space>
  );
}
