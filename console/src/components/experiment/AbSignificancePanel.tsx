import { Alert, Card, Space, Spin, Table, Tag, Tooltip, Typography } from 'antd';
import EErrorBar from '../charts/EErrorBar';
import type { AbBucketRow, AbReportData } from './abReport';
import { significantBuckets } from './abReport';

// 在线 A/B 结果看板:把最新一份 ab-report 的逐桶 CTR + Wilson CI + 显著性同屏呈现。
// 自身处理 加载/报错/暂无数据 三态,不崩页;放量控件在 ExperimentConsole 侧独立、任何态都可用。
export default function AbSignificancePanel({
  data,
  isLoading,
  isError,
  error,
}: {
  data?: AbReportData;
  isLoading: boolean;
  isError: boolean;
  error?: unknown;
}) {
  const title = <Typography.Text strong>在线 A/B 结果(显著性)</Typography.Text>;

  if (isLoading) {
    return (
      <Card size="small" title={title}>
        <Spin />
      </Card>
    );
  }

  if (isError) {
    const msg = error instanceof Error ? error.message : String(error ?? '未知错误');
    return (
      <Card size="small" title={title}>
        <Alert
          type="warning"
          showIcon
          message="读取 ab-report 失败"
          description={`离线报表接口暂不可用:${msg}。放量控件不受影响,可继续调整流量。`}
        />
      </Card>
    );
  }

  if (!data || data.file === null || data.rows.length === 0) {
    return (
      <Card size="small" title={title}>
        <Alert
          type="info"
          showIcon
          message="暂无 ab-report"
          description={
            <span>
              还没有在线分桶 CTR 报表。先跑离线作业生成:
              <br />
              <code className="mono">
                mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=ab-report
              </code>
              <br />
              需要线上已有带 bucket 标记的 IMPRESSION 曝光埋点(经 rec-engine 推荐后由 ExposureLogger 写入)。
            </span>
          }
        />
      </Card>
    );
  }

  const rows = data.rows;
  const hasSig = data.hasSignificance;
  const pct = (n: number) => (Number.isFinite(n) ? +(n * 100).toFixed(3) : 0);
  const pctStr = (n: number) => (Number.isFinite(n) ? (n * 100).toFixed(3) + '%' : 'n/a');

  const cats = rows.map((r) => r.bucket);
  const values = rows.map((r) => pct(r.ctr));
  // 有 CI 列则画须;否则退化为柱(low=high=ctr,须收拢不可见)。
  const low = rows.map((r) => (hasSig && Number.isFinite(r.ciLow) ? pct(r.ciLow) : pct(r.ctr)));
  const high = rows.map((r) => (hasSig && Number.isFinite(r.ciHigh) ? pct(r.ciHigh) : pct(r.ctr)));
  const markers = rows.map((r) => (r.isBaseline ? '基线' : r.significant === true ? '★ 显著' : null));

  const baseline = rows.find((r) => r.isBaseline);
  const sigRows = significantBuckets(rows);

  return (
    <Card
      size="small"
      title={title}
      extra={
        <Typography.Text type="secondary" className="mono" style={{ fontSize: 12 }}>
          {data.file.fileName}
        </Typography.Text>
      }
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          逐桶 CTR
          {hasSig ? ' · Wilson 95% CI · ★=相对基线显著' : '(旧格式:无 CI/显著性列)'}
          {baseline ? (
            <>
              {' · 基线桶='}
              <span className="mono">{baseline.bucket}</span>
            </>
          ) : null}
        </Typography.Text>

        <EErrorBar
          categories={cats}
          values={values}
          low={low}
          high={high}
          markers={markers}
          barName="CTR%"
          yName="%"
          height={340}
          fileName="ab-online-ctr.png"
        />

        {/* AA 校验提示:显著桶若与基线实为同策略,则分桶/埋点有偏。基于 report 数据的纯文案。 */}
        {hasSig ? (
          sigRows.length > 0 ? (
            <Alert
              type="warning"
              showIcon
              message="AA 校验提示"
              description={
                <span>
                  有 {sigRows.length} 个桶与基线 CTR 差异显著(
                  {sigRows.map((r) => r.bucket).join('、')})。若其中存在与基线<b>同策略</b>的桶(AA
                  测试),则分桶/埋点可能有偏,应先修分流再做正式 A/B;若确为不同策略,则这是真实的实验效果。
                </span>
              }
            />
          ) : (
            <Alert
              type="success"
              showIcon
              message="未发现显著差异桶"
              description="当前各桶 CTR 与基线的差异均不显著(可能是效果为零,或样本量不足——见明细的「最小样本/臂」)。"
            />
          )
        ) : null}

        <Table<AbBucketRow>
          size="small"
          rowKey="bucket"
          pagination={false}
          dataSource={rows}
          scroll={{ x: 'max-content' }}
          columns={[
            {
              title: '分桶',
              dataIndex: 'bucket',
              render: (v: string, r) => (
                <Space size={4}>
                  <span className="mono" style={{ fontSize: 12 }}>
                    {v}
                  </span>
                  {r.isBaseline ? <Tag>基线</Tag> : null}
                </Space>
              ),
            },
            { title: '曝光', dataIndex: 'impressions', align: 'right', render: (n: number) => (Number.isFinite(n) ? n : '-') },
            { title: '点击', dataIndex: 'clicks', align: 'right', render: (n: number) => (Number.isFinite(n) ? n : '-') },
            { title: 'CTR', dataIndex: 'ctr', align: 'right', render: (n: number) => pctStr(n) },
            {
              title: '95% CI',
              key: 'ci',
              render: (_: unknown, r) =>
                hasSig && Number.isFinite(r.ciLow) && Number.isFinite(r.ciHigh) ? (
                  <span className="mono" style={{ fontSize: 12 }}>
                    [{pctStr(r.ciLow)}, {pctStr(r.ciHigh)}]
                  </span>
                ) : (
                  <Typography.Text type="secondary">-</Typography.Text>
                ),
            },
            {
              title: 'lift',
              dataIndex: 'lift',
              align: 'right',
              render: (n: number, r) =>
                r.isBaseline || !Number.isFinite(n) ? (
                  <Typography.Text type="secondary">{r.isBaseline ? '(基线)' : '-'}</Typography.Text>
                ) : (
                  <span style={{ color: n >= 0 ? '#389e0d' : '#cf1322' }}>
                    {(n >= 0 ? '+' : '') + (n * 100).toFixed(2)}%
                  </span>
                ),
            },
            {
              title: 'p 值',
              dataIndex: 'pValue',
              align: 'right',
              render: (n: number, r) =>
                r.isBaseline || !Number.isFinite(n) ? (
                  <Typography.Text type="secondary">-</Typography.Text>
                ) : (
                  <span className="mono">{n < 0.0001 ? '<1e-4' : n.toFixed(4)}</span>
                ),
            },
            {
              title: '显著',
              dataIndex: 'significant',
              align: 'center',
              render: (v: boolean | null, r) =>
                r.isBaseline ? (
                  <Tag>基线</Tag>
                ) : v === null ? (
                  <Typography.Text type="secondary">-</Typography.Text>
                ) : v ? (
                  <Tag color="green">是</Tag>
                ) : (
                  <Tag>否</Tag>
                ),
            },
            {
              title: (
                <Tooltip title="检测到该 lift 所需的每臂最小样本量;∞ 表示 lift 过小、实际上不可检出">
                  <span>最小样本/臂</span>
                </Tooltip>
              ),
              dataIndex: 'minSample',
              align: 'right',
              render: (n: number, r) =>
                r.isBaseline ? (
                  <Typography.Text type="secondary">-</Typography.Text>
                ) : r.minSampleInf ? (
                  <Tag color="orange">∞</Tag>
                ) : Number.isFinite(n) ? (
                  <span className="mono">{n}</span>
                ) : (
                  <Typography.Text type="secondary">-</Typography.Text>
                ),
            },
          ]}
        />
      </Space>
    </Card>
  );
}
