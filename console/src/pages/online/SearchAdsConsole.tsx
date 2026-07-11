import { Suspense, lazy, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Input, InputNumber, Modal, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { getSearchAds, postAdClick, postAdConversion } from '../../api/ads';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import { useRequestHistory, type HistoryEntry } from '../../hooks/useRequestHistory';
import { useUrlParams } from '../../hooks/useUrlParams';
import type { SponsoredAd, SearchAdsResponse } from '../../api/types';
import { channelColor } from '../../components/explain/channelColors';
import { DollarOutlined, HistoryOutlined, ShareAltOutlined, ThunderboltOutlined } from '@ant-design/icons';
import FunnelBand from '../../components/funnel/FunnelBand';
import { ChartSkeleton } from '../../components/Skeletons';
import EmptyState from '../../components/EmptyState';
import PageHeader from '../../components/PageHeader';
import HistoryDrawer from '../../components/debug/HistoryDrawer';
import ResultDiff from '../../components/debug/ResultDiff';
import BiddingReplay from '../../components/debug/BiddingReplay';
import CollapsibleCard from '../../components/CollapsibleCard';
import { deriveAdStages } from '../../components/funnel/derive';
import { ACCENTS, BRAND, STATUS } from '../../theme/tokens';
import TracePanel from '../../components/explain/TracePanel';

type AdParams = {
  userId: number;
  scene: string;
  q: string;
  slots: number;
};

const adLabel = (p: AdParams) => `u${p.userId} · slots ${p.slots} · ${p.scene} · q="${p.q}"`;

// 竞价链路图含 echarts —— 本页是急加载路由,故懒加载,保 echarts 不进首屏包。
const AdBiddingChart = lazy(() => import('../../components/charts/AdBiddingChart'));

const fmt = (n: number, d = 4) => (Number.isFinite(n) ? n.toFixed(d) : '—');

export default function SearchAdsConsole() {
  const { userId, scene, setUserId, setScene } = useGlobalUser();
  const { message } = App.useApp();
  const { initial, write } = useUrlParams<AdParams>({ userId, scene, q: 'action', slots: 3 });
  const [q, setQ] = useState(initial.q);
  const [slots, setSlots] = useState(initial.slots);
  const [applied, setApplied] = useState<AdParams>(initial);

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
    queryKey: ['search-ads', applied],
    queryFn: () => getSearchAds(applied),
    enabled: !!applied.q.trim(),
  });

  const history = useRequestHistory<AdParams, SearchAdsResponse>();
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

  const requestId = query.data?.requestId ?? '';
  const ads = query.data?.ads ?? [];
  const stages = useMemo(() => deriveAdStages(ads), [ads]);
  const flowing = !!query.data && !query.isFetching;
  const selectedEntries = history.entries.filter((e) => history.selected.includes(e.id));

  const run = () => {
    if (!q.trim()) {
      message.warning('请输入 query');
      return;
    }
    setApplied({ userId, scene, q, slots });
  };

  const rerun = (e: HistoryEntry<AdParams, SearchAdsResponse>) => {
    const p = e.params;
    setQ(p.q);
    setSlots(p.slots);
    if (p.userId !== userId) setUserId(p.userId);
    if (p.scene !== scene) setScene(p.scene);
    setApplied(p);
    setDrawerOpen(false);
  };

  const click = async (adId: number) => {
    try {
      await postAdClick({ requestId, adId, userId });
      message.success(`点击已回传 · ad ${adId}(CPC 计费归因走 requestId)`);
    } catch (e) {
      message.error('点击回传失败: ' + toApiError(e).message);
    }
  };
  const convert = async (adId: number) => {
    try {
      await postAdConversion({ requestId, adId, userId });
      message.success(`转化已回传 · ad ${adId}`);
    } catch (e) {
      message.error('转化回传失败: ' + toApiError(e).message);
    }
  };

  const columns: ColumnsType<SponsoredAd> = [
    { title: '位次', dataIndex: 'position', width: 56, render: (p: number) => <b>{p}</b> },
    {
      title: '广告',
      key: 'ad',
      render: (_, r) => (
        <div>
          <div>
            <Typography.Text strong>{r.title || `#${r.itemId}`}</Typography.Text>
          </div>
          <Typography.Text type="secondary" className="mono" style={{ fontSize: 12 }}>
            adId={r.adId} · adv={r.advertiserId} · bidword={r.bidwordId}
          </Typography.Text>
        </div>
      ),
    },
    {
      title: '通道',
      dataIndex: 'channel',
      width: 110,
      render: (c: string) => <Tag color={channelColor(c)}>{c}</Tag>,
    },
    { title: '计费', dataIndex: 'bidType', width: 72, render: (b: string) => <Tag>{b}</Tag> },
    {
      title: <Tooltip title="出价 bid / 质量度 quality / 相关性 relevance">bid·q·rel</Tooltip>,
      key: 'bqr',
      width: 140,
      render: (_, r) => (
        <span className="mono" style={{ fontSize: 12 }}>
          {fmt(r.bid, 3)} · {fmt(r.quality, 2)} · {fmt(r.relevance, 2)}
        </span>
      ),
    },
    {
      title: <Tooltip title="原始 pCTR → 保序回归校准后">pCTR → 校准</Tooltip>,
      key: 'pctr',
      width: 150,
      render: (_, r) => {
        const down = r.pctrCalibrated < r.pctr;
        return (
          <span className="mono" style={{ fontSize: 12 }}>
            {fmt(r.pctr)}{' '}
            <span style={{ color: down ? '#cf1322' : '#389e0d' }}>→ {fmt(r.pctrCalibrated)}</span>
          </span>
        );
      },
    },
    {
      title: <Tooltip title="eCPM=排序依据(pacedBid·billFactor)">eCPM</Tooltip>,
      dataIndex: 'ecpm',
      width: 96,
      render: (v: number) => <span className="mono">{fmt(v)}</span>,
      sorter: (a, b) => b.ecpm - a.ecpm,
    },
    {
      title: <Tooltip title="GSP 次价:实际计费,通常 ≤ 自己的 eCPM">实收(GSP)</Tooltip>,
      dataIndex: 'chargedPrice',
      width: 110,
      render: (v: number, r) => (
        <Typography.Text strong style={{ color: BRAND }} className="mono">
          {fmt(v)}
          {r.chargedPrice < r.ecpm ? (
            <Tooltip title="次价 < eCPM,广告主省下价差">
              <Typography.Text type="success" style={{ fontSize: 11 }}>
                {' '}
                ↓
              </Typography.Text>
            </Tooltip>
          ) : null}
        </Typography.Text>
      ),
    },
    {
      title: '模拟',
      key: 'act',
      width: 140,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" disabled={!requestId} onClick={() => click(r.adId)}>
            点击
          </Button>
          <Button size="small" disabled={!requestId} onClick={() => convert(r.adId)}>
            转化
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="搜索广告调试台"
        accent={ACCENTS.ad}
        description="query 理解 → 广告召回 → 相关性门槛 → pCTR/pCVR → 校准 → oCPC 出价 → eCPM 竞价 → GSP 次价计费。"
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
          <Input value={q} onChange={(e) => setQ(e.target.value)} style={{ width: 240 }} onPressEnter={run} />
          <span>slots</span>
          <InputNumber min={1} max={10} value={slots} onChange={(v) => v && setSlots(v)} />
          <Button type="primary" loading={query.isFetching} onClick={run}>
            检索广告
          </Button>
          <Typography.Text type="secondary">userId={userId} · scene={scene}</Typography.Text>
        </Space>
      </Card>

      <FunnelBand
        dense
        stages={stages}
        flowing={flowing}
        status={flowing ? { color: STATUS.online, label: '在线', pulse: true } : undefined}
      />

      {ads.length > 0 ? (
        <>
          <Card size="small" title="竞价链路 · bid → eCPM → 实收(GSP)">
            <Suspense fallback={<ChartSkeleton height={340} />}>
              <AdBiddingChart ads={ads} />
            </Suspense>
          </Card>
          <CollapsibleCard title="竞价链路逐步重放" icon={<ThunderboltOutlined />} accent={ACCENTS.ad} defaultOpen={false}>
            <BiddingReplay ads={ads} />
          </CollapsibleCard>
        </>
      ) : null}

      <Card
        title={`赞助广告 (${ads.length})`}
        extra={query.data ? <TracePanel traceId={query.data.traceId} requestId={requestId} raw={query.data} /> : null}
      >
        {query.isError ? (
          <Alert type="error" message={toApiError(query.error).message} showIcon />
        ) : (
          <Table<SponsoredAd>
            size="small"
            rowKey="adId"
            columns={columns}
            dataSource={ads}
            loading={query.isFetching}
            pagination={false}
            scroll={{ x: 960 }}
            locale={{
              emptyText: (
                <EmptyState
                  icon={<DollarOutlined />}
                  accent={ACCENTS.ad}
                  title="暂无广告"
                  description="检查是否已 seed-ads、query 是否命中竞价词。"
                />
              ),
            }}
          />
        )}
      </Card>

      <HistoryDrawer<AdParams, SearchAdsResponse>
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
            {adLabel(e.params)} → {e.data.ads.length} 条
          </span>
        )}
      />

      <Modal title="A/B 竞价对比" open={compareOpen} onCancel={() => setCompareOpen(false)} footer={null} width={920}>
        {selectedEntries.length === 2 ? (
          <ResultDiff<SponsoredAd>
            aLabel={adLabel(selectedEntries[1].params)}
            bLabel={adLabel(selectedEntries[0].params)}
            aItems={selectedEntries[1].data.ads}
            bItems={selectedEntries[0].data.ads}
            keyOf={(t) => t.adId}
            primary={(t) => (
              <span>
                <Typography.Text strong>{t.title || `#${t.itemId}`}</Typography.Text>{' '}
                <span className="mono" style={{ fontSize: 12, color: '#8a94a6' }}>
                  ad{t.adId}
                </span>
              </span>
            )}
            columns={[
              { label: 'eCPM', get: (t) => t.ecpm, fmt: (n) => n.toFixed(4) },
              { label: '实收(GSP)', get: (t) => t.chargedPrice, fmt: (n) => n.toFixed(4) },
            ]}
          />
        ) : (
          <Typography.Text type="secondary">请在历史抽屉里勾选两条记录。</Typography.Text>
        )}
      </Modal>
    </Space>
  );
}
