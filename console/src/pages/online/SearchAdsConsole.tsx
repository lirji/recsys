import { Suspense, lazy, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Input, InputNumber, Space, Spin, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { getSearchAds, postAdClick, postAdConversion } from '../../api/ads';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import type { SponsoredAd } from '../../api/types';
import { channelColor } from '../../components/explain/channelColors';
import FunnelBand from '../../components/funnel/FunnelBand';
import { deriveAdStages } from '../../components/funnel/derive';
import { BRAND, STATUS } from '../../theme/tokens';
import TracePanel from '../../components/explain/TracePanel';

// 竞价链路图含 echarts —— 本页是急加载路由,故懒加载,保 echarts 不进首屏包。
const AdBiddingChart = lazy(() => import('../../components/charts/AdBiddingChart'));

const fmt = (n: number, d = 4) => (Number.isFinite(n) ? n.toFixed(d) : '—');

export default function SearchAdsConsole() {
  const { userId, scene } = useGlobalUser();
  const { message } = App.useApp();
  const [q, setQ] = useState('action');
  const [slots, setSlots] = useState(3);

  const query = useQuery({
    queryKey: ['search-ads'],
    queryFn: () => getSearchAds({ q, userId, slots, scene }),
    enabled: false,
  });

  const requestId = query.data?.requestId ?? '';
  const ads = query.data?.ads ?? [];
  const stages = useMemo(() => deriveAdStages(ads), [ads]);
  const flowing = !!query.data && !query.isFetching;

  const run = () => {
    if (!q.trim()) {
      message.warning('请输入 query');
      return;
    }
    query.refetch();
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
        <Card size="small" title="竞价链路 · bid → eCPM → 实收(GSP)">
          <Suspense fallback={<Spin />}>
            <AdBiddingChart ads={ads} />
          </Suspense>
        </Card>
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
            locale={{ emptyText: '暂无广告(检查是否已 seed-ads、query 是否命中竞价词)' }}
          />
        )}
      </Card>
    </Space>
  );
}
