import { Popover, Table, Tag, Typography } from 'antd';
import { ExperimentOutlined } from '@ant-design/icons';
import type { ScoreBreakdown } from '../../api/types';

// 单条候选的融合打分分解(来自 ?explain=true 的 explain.scores[itemId])。
// base = recallW·rNorm + rankW·rankScore + ftrl + bandit;final = base · boost · persBoost · debias。
// 点小徽章弹出各分量表格,不打扰主列表。

const fx = (n: number) => (Number.isFinite(n) ? n.toFixed(4) : '—');

const ROWS: { key: keyof ScoreBreakdown; label: string; hint: string }[] = [
  { key: 'rNorm', label: 'rNorm', hint: '归一化召回分' },
  { key: 'rankScore', label: 'rankScore', hint: '排序分(可校准)' },
  { key: 'ftrl', label: 'ftrl', hint: '近线 FTRL 加成' },
  { key: 'bandit', label: 'bandit', hint: 'bandit 探索加成' },
  { key: 'base', label: 'base', hint: '加性融合基分' },
  { key: 'boost', label: 'boost', hint: '通道乘性加成' },
  { key: 'persBoost', label: 'persBoost', hint: '个性化亲和加成' },
  { key: 'debias', label: 'debias', hint: '流行度去偏乘子' },
  { key: 'finalScore', label: 'final', hint: '最终融合分' },
];

export default function ScoreBreakdownPopover({ breakdown }: { breakdown: ScoreBreakdown }) {
  const data = ROWS.map((r) => ({ key: r.key, label: r.label, hint: r.hint, value: breakdown[r.key] }));
  const content = (
    <Table
      size="small"
      pagination={false}
      showHeader={false}
      style={{ width: 280 }}
      dataSource={data}
      columns={[
        {
          dataIndex: 'label',
          render: (v: string, row) => (
            <span>
              <Typography.Text strong className="mono">
                {v}
              </Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 11, marginLeft: 6 }}>
                {row.hint}
              </Typography.Text>
            </span>
          ),
        },
        {
          dataIndex: 'value',
          align: 'right' as const,
          render: (v: number, row) => (
            <Typography.Text strong={row.label === 'final' || row.label === 'base'} className="mono">
              {fx(v)}
            </Typography.Text>
          ),
        },
      ]}
    />
  );
  return (
    <Popover content={content} title="打分分解" trigger="click" placement="left">
      <Tag icon={<ExperimentOutlined />} color="purple" style={{ cursor: 'pointer', margin: 0 }}>
        分解
      </Tag>
    </Popover>
  );
}
