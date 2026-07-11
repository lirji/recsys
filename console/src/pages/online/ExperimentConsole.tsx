import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Divider, InputNumber, Popconfirm, Space, Spin, Switch, Table, Tag, Tooltip, Typography } from 'antd';
import {
  clearOverride,
  getExperiment,
  setGlobalEnabled,
  setLayerEnabled,
  setVariantWeight,
} from '../../api/experiment';
import { toApiError } from '../../api/client';
import AbSignificancePanel from '../../components/experiment/AbSignificancePanel';
import { useAbReport, variantOnlineStat } from '../../components/experiment/abReport';

export default function ExperimentConsole() {
  const { message } = App.useApp();
  const query = useQuery({ queryKey: ['experiment'], queryFn: getExperiment });
  const snap = query.data;

  // 在线 A/B 结果(最新 ab-report):独立取数,任何态都不阻塞放量控件。
  const abQuery = useAbReport();
  const abRows = abQuery.data?.rows ?? [];

  // 可编辑权重副本,键 `${layer}/${variant}`。
  const [weights, setWeights] = useState<Record<string, number>>({});
  useEffect(() => {
    if (!snap) return;
    const next: Record<string, number> = {};
    for (const [layer, cfg] of Object.entries(snap.staticLayers)) {
      for (const [variant, w] of Object.entries(cfg.variants)) next[`${layer}/${variant}`] = w;
    }
    setWeights(next);
  }, [snap]);

  const guard = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      const r = (await fn()) as Record<string, unknown>;
      if (r && r.ok === false) message.warning(`未生效: ${String(r.reason ?? '')}`);
      else message.success(ok);
      query.refetch();
    } catch (e) {
      message.error(toApiError(e).message);
    }
  };

  if (query.isLoading) return <Spin />;
  if (query.isError) return <Alert type="error" showIcon message={toApiError(query.error).message} />;
  if (!snap) return null;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card>
        <Space align="center" size={16} wrap>
          <Typography.Text strong>全局实验开关</Typography.Text>
          <Switch
            checked={snap.staticEnabled}
            onChange={(v) => guard(() => setGlobalEnabled(v), `全局实验 ${v ? '开启' : '关闭'}`)}
          />
          <Typography.Text type="secondary">
            开关/放量写 Redis 覆盖层,改实验不重启;下方权重滑块调完立即对后续请求生效。
          </Typography.Text>
          <Popconfirm title="清空所有 Redis 覆盖,回落到 yml 静态配置?" onConfirm={() => guard(clearOverride, '已清空 override')}>
            <Button danger>清除 override</Button>
          </Popconfirm>
        </Space>
      </Card>

      <AbSignificancePanel
        data={abQuery.data}
        isLoading={abQuery.isLoading}
        isError={abQuery.isError}
        error={abQuery.error}
      />

      {Object.entries(snap.staticLayers).map(([layer, cfg]) => (
        <Card
          key={layer}
          size="small"
          title={
            <Space>
              <Typography.Text strong>层:{layer}</Typography.Text>
              {cfg.salt ? <Typography.Text type="secondary" className="mono">salt={cfg.salt}</Typography.Text> : null}
            </Space>
          }
          extra={
            <Space>
              <Typography.Text type="secondary">本层开关</Typography.Text>
              <Switch
                defaultChecked
                onChange={(v) => guard(() => setLayerEnabled(layer, v), `层 ${layer} ${v ? '开启' : '关闭'}`)}
              />
            </Space>
          }
        >
          <Table
            size="small"
            rowKey="variant"
            pagination={false}
            dataSource={Object.keys(cfg.variants).map((variant) => ({ variant }))}
            columns={[
              { title: '变体', dataIndex: 'variant', width: 200 },
              {
                title: '流量权重(0=停止)',
                key: 'weight',
                render: (_, row) => {
                  const key = `${layer}/${row.variant}`;
                  return (
                    <Space>
                      <InputNumber
                        min={0}
                        max={100}
                        value={weights[key] ?? 0}
                        onChange={(v) => setWeights((w) => ({ ...w, [key]: v ?? 0 }))}
                      />
                      <Button
                        size="small"
                        type="primary"
                        onClick={() =>
                          guard(() => setVariantWeight(layer, row.variant, weights[key] ?? 0), `已设 ${key}=${weights[key] ?? 0}`)
                        }
                      >
                        保存
                      </Button>
                    </Space>
                  );
                },
              },
              {
                title: (
                  <Tooltip title="按变体名匹配 ab-report 分桶(bucket 内的「层:变体」token),跨桶聚合曝光/点击得该变体的边际在线 CTR;命名对不上则显示无匹配。">
                    <span>在线 CTR / 显著?</span>
                  </Tooltip>
                ),
                key: 'online',
                width: 240,
                render: (_, row) => {
                  if (abQuery.isLoading) return <Typography.Text type="secondary">加载中…</Typography.Text>;
                  if (abRows.length === 0) return <Typography.Text type="secondary">—</Typography.Text>;
                  const st = variantOnlineStat(abRows, layer, row.variant);
                  if (!st) return <Typography.Text type="secondary">无匹配曝光</Typography.Text>;
                  return (
                    <Space size={6}>
                      <Typography.Text className="mono">
                        {Number.isFinite(st.ctr) ? (st.ctr * 100).toFixed(2) + '%' : 'n/a'}
                      </Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        · {st.buckets}桶/{st.impressions}曝光
                      </Typography.Text>
                      {st.anySignificant ? <Tag color="green">含显著桶</Tag> : null}
                    </Space>
                  );
                },
              },
            ]}
          />
        </Card>
      ))}

      <Card size="small" title="当前 Redis 覆盖 (overrides)">
        <Divider style={{ margin: '4px 0 12px' }} />
        <pre className="json-block">{JSON.stringify(snap.overrides ?? {}, null, 2)}</pre>
      </Card>
    </Space>
  );
}
