import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Input, InputNumber, Modal, Space, Typography } from 'antd';
import { getSearch } from '../../api/recommend';
import { makeEvent, reportBehavior } from '../../api/behavior';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import { useRequestHistory, type HistoryEntry } from '../../hooks/useRequestHistory';
import { useUrlParams } from '../../hooks/useUrlParams';
import { useItemMeta } from '../../hooks/useItemMeta';
import { HistoryOutlined, SearchOutlined, ShareAltOutlined } from '@ant-design/icons';
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

type SearchParams = {
  userId: number;
  size: number;
  q: string;
};

const label = (p: SearchParams) => `u${p.userId} · size ${p.size} · q="${p.q}"`;

export default function SearchConsole() {
  const { userId, setUserId } = useGlobalUser();
  const { message } = App.useApp();
  const { initial, write } = useUrlParams<SearchParams>({ userId, size: 10, q: 'action' });
  const [size, setSize] = useState(initial.size);
  const [q, setQ] = useState(initial.q);
  const [applied, setApplied] = useState<SearchParams>(initial);

  useEffect(() => {
    if (initial.userId !== userId) setUserId(initial.userId);
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
    queryKey: ['search', applied],
    // 调试台默认带 explain=true:真实逐阶段计数 + 打分分解(explain 请求后端旁路缓存)。
    queryFn: () => getSearch({ ...applied, explain: true }),
    enabled: !!applied.q.trim(),
  });

  const history = useRequestHistory<SearchParams, RecommendResponse>();
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

  const items = query.data?.items ?? [];
  const maxScore = items.reduce((m, it) => Math.max(m, it.score), 0);
  const itemMeta = useItemMeta(items.map((it) => it.itemId));
  const explain = query.data?.explain ?? null;
  const stages = useMemo(() => deriveRecStages(items, explain), [items, explain]);
  const flowing = !!query.data && !query.isFetching;
  const selectedEntries = history.entries.filter((e) => history.selected.includes(e.id));

  const run = () => {
    if (!q.trim()) {
      message.warning('请输入搜索词 q');
      return;
    }
    setApplied({ userId, size, q });
  };

  const rerun = (e: HistoryEntry<SearchParams, RecommendResponse>) => {
    const p = e.params;
    setSize(p.size);
    setQ(p.q);
    if (p.userId !== userId) setUserId(p.userId);
    setApplied(p);
    setDrawerOpen(false);
  };

  const report = async (itemId: number) => {
    try {
      await reportBehavior(makeEvent(userId, itemId, 'CLICK', 'search'));
      message.success(`已上报点击 · item ${itemId}`);
    } catch (e) {
      message.error('上报失败: ' + toApiError(e).message);
    }
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="搜索调试台"
        accent={ACCENTS.rank}
        description="query 驱动:混合检索(词法 + 向量 RRF)→ 相关性主导排序,冷用户带 query 也走此链路。"
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
          <span>q</span>
          <Input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ width: 260 }}
            placeholder="搜索词"
            onPressEnter={run}
          />
          <span>size</span>
          <InputNumber min={1} max={200} value={size} onChange={(v) => v && setSize(v)} />
          <Button type="primary" loading={query.isFetching} onClick={run}>
            搜索
          </Button>
          <Typography.Text type="secondary">userId={userId}(query 驱动:相关性主导,冷用户也走此链路)</Typography.Text>
        </Space>
      </Card>

      <FunnelBand
        dense
        stages={stages}
        flowing={flowing}
        status={flowing ? { color: STATUS.online, label: '在线', pulse: true } : undefined}
      />

      <Card
        title={`搜索结果 (${items.length})`}
        extra={query.data ? <TracePanel traceId={query.data.traceId} raw={query.data} /> : null}
      >
        {query.isError ? (
          <Alert type="error" message={toApiError(query.error).message} showIcon />
        ) : query.isFetching && !query.data ? (
          <ResultRowsSkeleton rows={size > 8 ? 8 : size} />
        ) : items.length === 0 ? (
          <EmptyState
            icon={<SearchOutlined />}
            accent={ACCENTS.rank}
            title="暂无搜索结果"
            description="换个搜索词试试,或检查语料 / 向量是否就绪。"
            action={
              <Button type="primary" onClick={run}>
                搜索
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
                  <Button size="small" type="primary" ghost onClick={() => report(it.itemId)}>
                    👍 点击
                  </Button>
                }
              />
            ))}
          </Space>
        )}
      </Card>

      <HistoryDrawer<SearchParams, RecommendResponse>
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
