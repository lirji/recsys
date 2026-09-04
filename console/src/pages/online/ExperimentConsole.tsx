import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Alert, Button, Card, Divider, InputNumber, Popconfirm, Space, Switch, Tag, Tooltip, Typography } from 'antd';
import {
  clearOverride,
  getExperiment,
  setGlobalEnabled,
  setLayerEnabled,
  setVariantWeight,
} from '../../api/experiment';
import { queryKeys } from '../../api/queryKeys';
import { toApiError } from '../../api/client';
import type { ExperimentWriteResult } from '../../api/types';
import AbSignificancePanel from '../../components/experiment/AbSignificancePanel';
import { useAbReport, variantOnlineStat } from '../../components/experiment/abReport';
import PageHeader from '../../components/PageHeader';
import { ResultRowsSkeleton } from '../../components/Skeletons';
import { ACCENTS } from '../../theme/tokens';

const LAYER_META: Record<string, { label: string; hint: string }> = {
  recall: { label: '召回', hint: '通道组合,进推荐 bucketTag' },
  rank: { label: '排序', hint: '打分策略' },
  rerank: { label: '重排', hint: '多样性 / MMR / DPP' },
  ad: { label: '广告', hint: 'reserve-price 等;单独分桶,不混入推荐 bucketTag' },
};

export default function ExperimentConsole() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: queryKeys.experiment(), queryFn: getExperiment });
  const snap = query.data;
  const abQuery = useAbReport();
  const abRows = abQuery.data?.rows ?? [];

  const [weights, setWeights] = useState<Record<string, number>>({});
  useEffect(() => {
    if (!snap) return;
    const next: Record<string, number> = {};
    for (const [layer, cfg] of Object.entries(snap.staticLayers)) {
      for (const [variant, w] of Object.entries(cfg.variants)) next[`${layer}/${variant}`] = w;
    }
    setWeights(next);
  }, [snap]);

  const applyWrite = async (fn: () => Promise<ExperimentWriteResult>, ok: string) => {
    const r = await fn();
    if (r && r.ok === false) {
      message.warning(`未生效: ${r.reason ?? ''}`);
    } else {
      message.success(ok);
    }
    await queryClient.invalidateQueries({ queryKey: queryKeys.experiment() });
    return r;
  };

  const globalMut = useMutation({
    mutationFn: (v: boolean) => applyWrite(() => setGlobalEnabled(v), `全局实验 ${v ? '开启' : '关闭'}`),
    onError: (e) => message.error(toApiError(e).message),
  });
  const layerMut = useMutation({
    mutationFn: ({ layer, value }: { layer: string; value: boolean }) =>
      applyWrite(() => setLayerEnabled(layer, value), `层 ${layer} ${value ? '开启' : '关闭'}`),
    onError: (e) => message.error(toApiError(e).message),
  });
  const saveLayerMut = useMutation({
    mutationFn: async ({ layer, variants }: { layer: string; variants: string[] }) => {
      for (const variant of variants) {
        const r = await setVariantWeight(layer, variant, weights[`${layer}/${variant}`] ?? 0);
        if (r && r.ok === false) throw new Error(r.reason ?? '未生效');
      }
    },
    onSuccess: async (_, { layer }) => {
      message.success(`已保存 ${layer} 层权重`);
      await queryClient.invalidateQueries({ queryKey: queryKeys.experiment() });
    },
    onError: (e) => message.error(toApiError(e).message),
  });
  const clearMut = useMutation({
    mutationFn: () => applyWrite(clearOverride, '已清空 override'),
    onError: (e) => message.error(toApiError(e).message),
  });

  if (query.isLoading) return <ResultRowsSkeleton rows={4} />;
  if (query.isError) return <Alert type="error" showIcon message={toApiError(query.error).message} />;
  if (!snap) return null;

  const globalOn = snap.enabled ?? snap.staticEnabled;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="实验管理"
        accent={ACCENTS.rank}
        description="分层 A/B,改权重写 Redis,不用重启。"
      />
      <Card>
        <Space align="center" size={16} wrap>
          <Typography.Text strong>全局实验开关</Typography.Text>
          <Switch
            checked={globalOn}
            loading={globalMut.isPending}
            onChange={(v) => globalMut.mutate(v)}
          />
          <Typography.Text type="secondary">写 Redis 覆盖层,点保存后对后续请求生效。</Typography.Text>
          <Popconfirm title="清空所有 Redis 覆盖,回落到 yml 静态配置?" onConfirm={() => clearMut.mutate()}>
            <Button danger loading={clearMut.isPending}>
              清除 override
            </Button>
          </Popconfirm>
        </Space>
      </Card>

      <Card size="small" title="分层放量">
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          {Object.entries(snap.staticLayers).map(([layer, cfg]) => {
            const meta = LAYER_META[layer];
            const variants = Object.keys(cfg.variants);
            return (
              <div key={layer} className={`exp-layer${layer === 'ad' ? ' exp-layer-ad' : ''}`}>
                <Space align="center" wrap size={8} style={{ minWidth: 220 }}>
                  <Switch
                    checked={cfg.enabled !== false}
                    loading={layerMut.isPending}
                    onChange={(v) => layerMut.mutate({ layer, value: v })}
                  />
                  <Tooltip title={meta?.hint}>
                    <Typography.Text strong>{meta?.label ?? layer}</Typography.Text>
                  </Tooltip>
                  <Tag>{layer}</Tag>
                  {layer === 'ad' ? <Tag color="gold">广告专用分桶</Tag> : null}
                </Space>
                <Space align="center" wrap size={10} style={{ flex: 1, justifyContent: 'flex-end' }}>
                  {variants.map((variant) => {
                    const key = `${layer}/${variant}`;
                    const st = abRows.length ? variantOnlineStat(abRows, layer, variant) : null;
                    return (
                      <Space key={variant} size={6}>
                        <Typography.Text className="mono" style={{ fontSize: 12 }}>
                          {variant}
                        </Typography.Text>
                        <InputNumber
                          min={0}
                          max={100}
                          size="small"
                          value={weights[key] ?? 0}
                          onChange={(v) => setWeights((w) => ({ ...w, [key]: v ?? 0 }))}
                        />
                        {st ? (
                          <Typography.Text type="secondary" className="mono" style={{ fontSize: 12 }}>
                            {(st.ctr * 100).toFixed(2)}%
                            {st.anySignificant ? <Tag color="green" style={{ marginInlineStart: 4 }}>显著</Tag> : null}
                          </Typography.Text>
                        ) : null}
                      </Space>
                    );
                  })}
                  <Button
                    size="small"
                    type="primary"
                    loading={saveLayerMut.isPending}
                    onClick={() => saveLayerMut.mutate({ layer, variants })}
                  >
                    保存
                  </Button>
                </Space>
              </div>
            );
          })}
        </Space>
      </Card>

      <AbSignificancePanel
        data={abQuery.data}
        isLoading={abQuery.isLoading}
        isError={abQuery.isError}
        error={abQuery.error}
      />

      <Card size="small" title="当前 Redis 覆盖 (overrides)">
        <Divider style={{ margin: '4px 0 12px' }} />
        <pre className="json-block">{JSON.stringify(snap.overrides ?? {}, null, 2)}</pre>
      </Card>
    </Space>
  );
}
