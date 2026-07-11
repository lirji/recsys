import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Input, InputNumber, Modal, Space, Typography } from 'antd';
import { getRecommend } from '../../api/recommend';
import { makeEvent, reportBehavior } from '../../api/behavior';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import { useRequestHistory, type HistoryEntry } from '../../hooks/useRequestHistory';
import { useUrlParams } from '../../hooks/useUrlParams';
import { useItemMeta } from '../../hooks/useItemMeta';
import { HistoryOutlined, ShareAltOutlined, ThunderboltOutlined } from '@ant-design/icons';
import ItemCard from '../../components/explain/ItemCard';
import { ResultRowsSkeleton } from '../../components/Skeletons';
import EmptyState from '../../components/EmptyState';
import PageHeader from '../../components/PageHeader';
import HistoryDrawer from '../../components/debug/HistoryDrawer';
import ResultDiff from '../../components/debug/ResultDiff';
import FunnelBand from '../../components/funnel/FunnelBand';
import { deriveRecStages } from '../../components/funnel/derive';
import { ACCENTS, STATUS } from '../../theme/tokens';
import TracePanel from '../../components/explain/TracePanel';
import type { RecommendItem, RecommendResponse } from '../../api/types';

type RecParams = {
  userId: number;
  size: number;
  scene: string;
  q: string;
};

const label = (p: RecParams) => `u${p.userId} · size ${p.size} · ${p.scene}${p.q ? ` · q="${p.q}"` : ''}`;

export default function RecommendConsole() {
  const { userId, scene, setUserId, setScene } = useGlobalUser();
  const { message } = App.useApp();
  const { initial, write } = useUrlParams<RecParams>({ userId, size: 10, scene, q: '' });
  const [size, setSize] = useState(initial.size);
  const [q, setQ] = useState(initial.q);
  // applied = 真正发起请求的参数快照;进 queryKey → 切参数缓存正确、重跑可靠。切顶栏 userId 不自动重取(仍按钮驱动)。
  const [applied, setApplied] = useState<RecParams>(initial);

  // URL 带参进入时,把全局 userId/scene 同步成链接里的值(一次);applied 变化写回 URL → 可复制分享。
  useEffect(() => {
    if (initial.userId !== userId) setUserId(initial.userId);
    if (initial.scene !== scene) setScene(initial.scene);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    write(applied);
  }, [applied, write]);

  const shareLink = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href);
      message.success('已复制分享链接(含当前参数)');
    } catch {
      message.error('复制失败');
    }
  };

  const query = useQuery({
    queryKey: ['recommend', applied],
    // 调试台默认带 explain=true:拿真实逐阶段计数 + 打分分解(explain 请求后端旁路缓存)。
    queryFn: () => getRecommend({ ...applied, explain: true }),
  });

  const history = useRequestHistory<RecParams, RecommendResponse>();
  const fetchStart = useRef(0);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [compareOpen, setCompareOpen] = useState(false);

  useEffect(() => {
    if (query.isFetching) fetchStart.current = performance.now();
  }, [query.isFetching]);
  useEffect(() => {
    if (query.isSuccess && query.data) {
      history.push(applied, query.data, Math.max(0, Math.round(performance.now() - fetchStart.current)));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query.dataUpdatedAt]);

  const run = () => setApplied({ userId, size, scene, q });

  const rerun = (e: HistoryEntry<RecParams, RecommendResponse>) => {
    const p = e.params;
    setSize(p.size);
    setQ(p.q);
    if (p.userId !== userId) setUserId(p.userId);
    if (p.scene !== scene) setScene(p.scene);
    setApplied(p);
    setDrawerOpen(false);
  };

  const items = query.data?.items ?? [];
  const maxScore = items.reduce((m, it) => Math.max(m, it.score), 0);
  const itemMeta = useItemMeta(items.map((it) => it.itemId));
  const explain = query.data?.explain ?? null;
  const stages = useMemo(() => deriveRecStages(items, explain), [items, explain]);
  const flowing = !!query.data && !query.isFetching;

  // 选中做对比的两条(entries 已按时间倒序 → [0]=较新=B,[1]=较旧=A)。
  const selectedEntries = history.entries.filter((e) => history.selected.includes(e.id));

  const report = async (itemId: number, action: 'IMPRESSION' | 'CLICK') => {
    try {
      await reportBehavior(makeEvent(userId, itemId, action, scene));
      message.success(`已上报 ${action} · item ${itemId}`);
    } catch (e) {
      message.error('上报失败: ' + toApiError(e).message);
    }
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="推荐调试台"
        accent={ACCENTS.recall}
        description="个性化推荐链路:多通道召回 → 排序 → 融合 → 重排 → 截断。带 q 走 query 驱动。"
        extra={
          <Space>
            <Button icon={<ShareAltOutlined />} onClick={shareLink}>
              分享
            </Button>
            <Button icon={<HistoryOutlined />} onClick={() => setDrawerOpen(true)}>
              历史 ({history.entries.length})
            </Button>
          </Space>
        }
      />
      <Card size="small" bordered={false}>
        <Space wrap>
          <span>size</span>
          <InputNumber min={1} max={200} value={size} onChange={(v) => v && setSize(v)} />
          <span>q(可选,带 q 走 query 驱动)</span>
          <Input
            allowClear
            placeholder="留空=纯个性化推荐"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ width: 240 }}
            onPressEnter={run}
          />
          <Button type="primary" loading={query.isFetching} onClick={run}>
            获取推荐
          </Button>
          <Typography.Text type="secondary">
            userId={userId} · scene={scene}
          </Typography.Text>
        </Space>
      </Card>

      <FunnelBand
        dense
        stages={stages}
        flowing={flowing}
        status={flowing ? { color: STATUS.online, label: '在线', pulse: true } : undefined}
      />

      <Card
        title={`推荐结果 (${items.length})`}
        extra={query.data ? <TracePanel traceId={query.data.traceId} raw={query.data} /> : null}
      >
        {query.isError ? (
          <Alert type="error" message={toApiError(query.error).message} showIcon />
        ) : query.isFetching && !query.data ? (
          <ResultRowsSkeleton rows={size > 8 ? 8 : size} />
        ) : items.length === 0 ? (
          <EmptyState
            icon={<ThunderboltOutlined />}
            accent={ACCENTS.recall}
            title="暂无推荐结果"
            description="检查是否已灌数据 / 向量,或 rec-engine 是否启动。"
            action={
              <Button type="primary" onClick={run}>
                获取推荐
              </Button>
            }
          />
        ) : (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {items.map((it, i) => (
              <ItemCard
                key={it.itemId}
                item={it}
                rank={i + 1}
                maxScore={maxScore}
                meta={itemMeta(it.itemId)}
                breakdown={explain?.scores?.[it.itemId]}
                actions={
                  <>
                    <Button size="small" onClick={() => report(it.itemId, 'IMPRESSION')}>
                      曝光
                    </Button>
                    <Button size="small" type="primary" ghost onClick={() => report(it.itemId, 'CLICK')}>
                      👍 点击
                    </Button>
                  </>
                }
              />
            ))}
          </Space>
        )}
      </Card>

      <HistoryDrawer<RecParams, RecommendResponse>
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        entries={history.entries}
        selected={history.selected}
        onToggleSelect={history.toggleSelect}
        onRerun={rerun}
        onClear={history.clear}
        onCompare={() => {
          setCompareOpen(true);
          setDrawerOpen(false);
        }}
        renderSummary={(e) => (
          <span className="mono" style={{ fontSize: 12 }}>
            {label(e.params)} → {e.data.items.length} 条
          </span>
        )}
      />

      <Modal title="A/B 结果对比" open={compareOpen} onCancel={() => setCompareOpen(false)} footer={null} width={880}>
        {selectedEntries.length === 2 ? (
          <ResultDiff<RecommendItem>
            aLabel={label(selectedEntries[1].params)}
            bLabel={label(selectedEntries[0].params)}
            aItems={selectedEntries[1].data.items}
            bItems={selectedEntries[0].data.items}
            keyOf={(t) => t.itemId}
            primary={(t) => <span className="mono">#{t.itemId}</span>}
            columns={[{ label: '分数', get: (t) => t.score, fmt: (n) => n.toFixed(4) }]}
          />
        ) : (
          <Typography.Text type="secondary">请在历史抽屉里勾选两条记录。</Typography.Text>
        )}
      </Modal>
    </Space>
  );
}
