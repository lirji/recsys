import type { KeyboardEvent } from 'react';
import { Typography } from 'antd';
import type { ChannelStat } from './ChannelBreakdown';
import { channelColor } from './channelColors';
import { BRAND, hexOfPreset, rgba } from '../../theme/tokens';

// 召回通道过滤条 / 命中热点:每个「有存活」的通道一枚彩色 chip(带最终存活数),点击即按该路过滤结果、
// 再点或点「全部」还原。与 ChannelBreakdown 表格行点击共享同一 selectedChannel(双入口 · 同状态)。
// 纯展示,不含数据逻辑;chip 颜色取自全站统一的 channelColor,与 RecallTags / 表格 Tag 同色。
export default function ChannelFilterBar({
  stats,
  selectedChannel,
  onSelectChannel,
}: {
  stats: ChannelStat[];
  selectedChannel: string | null;
  onSelectChannel: (ch: string | null) => void;
}) {
  // 命中热点 = 有 item 最终存活的通道,按存活数降序(与结果的实际贡献一致)。
  const live = stats.filter((s) => s.survived > 0).sort((a, b) => b.survived - a.survived);
  if (live.length === 0) return null;

  const pill = (opts: {
    key: string;
    label: string;
    hex: string;
    active: boolean;
    count?: number;
    onClick: () => void;
  }) => {
    const { key, label, hex, active, count, onClick } = opts;
    const onKey = (e: KeyboardEvent<HTMLSpanElement>) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        onClick();
      }
    };
    return (
      <span
        key={key}
        role="button"
        tabIndex={0}
        aria-pressed={active}
        onClick={onClick}
        onKeyDown={onKey}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          height: 26,
          padding: '0 10px',
          borderRadius: 13,
          cursor: 'pointer',
          userSelect: 'none',
          fontSize: 12,
          lineHeight: 1,
          color: active ? hex : '#5a6475',
          background: active ? rgba(hex, 0.12) : '#f4f6fa',
          border: `1px solid ${active ? rgba(hex, 0.42) : 'transparent'}`,
          transition: 'background .15s, border-color .15s, color .15s',
        }}
      >
        <span
          aria-hidden
          style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            flex: '0 0 auto',
            background: hex,
            boxShadow: active ? `0 0 6px 1px ${rgba(hex, 0.6)}` : 'none',
          }}
        />
        <span style={{ fontWeight: active ? 600 : 500 }}>{label}</span>
        {count != null ? (
          <span className="mono" style={{ fontSize: 11, color: active ? hex : '#98a1b2' }}>
            {count}
          </span>
        ) : null}
      </span>
    );
  };

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
      <Typography.Text type="secondary" style={{ fontSize: 12, marginRight: 2 }}>
        按通道过滤
      </Typography.Text>
      {pill({
        key: '__all__',
        label: '全部',
        hex: BRAND,
        active: selectedChannel == null,
        onClick: () => onSelectChannel(null),
      })}
      {live.map((s) =>
        pill({
          key: s.channel,
          label: s.channel,
          hex: hexOfPreset(channelColor(s.channel)),
          active: selectedChannel === s.channel,
          count: s.survived,
          onClick: () => onSelectChannel(selectedChannel === s.channel ? null : s.channel),
        }),
      )}
    </div>
  );
}
