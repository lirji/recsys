import { useMemo, useState, type ReactNode } from 'react';
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Col,
  Row,
  Segmented,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
  type TableColumnsType,
} from 'antd';
import {
  AimOutlined,
  BranchesOutlined,
  ExperimentOutlined,
  InfoCircleOutlined,
  PartitionOutlined,
  RiseOutlined,
  StarOutlined,
  TrophyOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { getExperiment } from '../../api/experiment';
import { toApiError } from '../../api/client';
import {
  useAbReport,
  significantBuckets,
  variantOnlineStat,
  type AbBucketRow,
  type VariantOnlineStat,
} from '../../components/experiment/abReport';
import PageHeader from '../../components/PageHeader';
import StatCard from '../../components/StatCard';
import CollapsibleCard from '../../components/CollapsibleCard';
import EmptyState from '../../components/EmptyState';
import { ChartSkeleton, StatCardsSkeleton } from '../../components/Skeletons';
import EErrorBar from '../../components/charts/EErrorBar';
import EBar from '../../components/charts/EBar';
import { channelColor } from '../../components/explain/channelColors';
import { ACCENTS } from '../../theme/tokens';
import type { ExperimentSnapshot } from '../../api/types';

// 桶对比大盘:把最新一份 ab-report 重编排成「结论台」——一屏读出哪些桶显著赢/输基线、样本够不够、是否疑似 AA 有偏。
// 纯前端、零后端改动:①~⑤ 由 useAbReport() 驱动,⑥(层×变体汇总)由 getExperiment() 独立驱动,两路解耦互不阻塞。
// 一切颜色/图标/文案/降级分支照抄设计规格(docs/design-bucket-board.md)。

type ViewKey = '完整分桶' | '层×变体汇总';
type SortKey = '原始' | 'CTR' | 'lift' | 'p 值' | '曝光';

const pct = (n: number) => (Number.isFinite(n) ? +(n * 100).toFixed(3) : 0);
const pctStr = (n: number) => (Number.isFinite(n) ? (n * 100).toFixed(3) + '%' : 'n/a');

const GRAY = '#8a94a6';
const GREEN = '#389e0d';
const RED = '#cf1322';

export default function BucketBoard() {
  const navigate = useNavigate();

  // ①~⑤ 主数据源:最新 ab-report。
  const abQuery = useAbReport();
  // ⑥ 配置侧:枚举层×变体。独立取数,失败不影响完整分桶段。
  const expQuery = useQuery({ queryKey: ['experiment'], queryFn: getExperiment });

  const [view, setView] = useState<ViewKey>('完整分桶');
  const [sortKey, setSortKey] = useState<SortKey>('原始');
  const [onlySig, setOnlySig] = useState(false);

  const data = abQuery.data;
  const rows = data?.rows ?? [];
  const hasSig = data?.hasSignificance ?? false;

  // 仅显著筛选:基线恒保留(§8)。
  const filtered = useMemo(
    () => (onlySig ? rows.filter((r) => r.isBaseline || r.significant === true) : rows),
    [rows, onlySig],
  );

  // 图与表同源的单一排序结果:基线/NaN 永远沉底(§8)。
  const sortedRows = useMemo(() => {
    if (sortKey === '原始') return filtered;
    const key = ({ CTR: 'ctr', lift: 'lift', 'p 值': 'pValue', 曝光: 'impressions' } as const)[sortKey];
    const asc = sortKey === 'p 值'; // p 值升序(越小越显著),其余降序
    const bad = (r: AbBucketRow) => r.isBaseline || !Number.isFinite(r[key]);
    return [...filtered].sort((a, b) => {
      if (bad(a) && bad(b)) return 0;
      if (bad(a)) return 1;
      if (bad(b)) return -1;
      return asc ? a[key] - b[key] : b[key] - a[key];
    });
  }, [filtered, sortKey]);

  const fileName = data?.file?.fileName;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {/* ① PageHeader —— 任何态都渲染,给页面稳定骨架。 */}
      <PageHeader
        title="桶对比大盘"
        accent={ACCENTS.rank}
        description="一屏读懂:哪些桶显著赢/输基线、样本够不够、是否疑似 AA 有偏。"
        extra={
          fileName ? (
            <Typography.Text type="secondary" className="mono" style={{ fontSize: 12 }}>
              {fileName}
            </Typography.Text>
          ) : undefined
        }
      />

      {abQuery.isLoading ? (
        <LoadingBody />
      ) : abQuery.isError ? (
        <Alert
          type="warning"
          showIcon
          message="读取 ab-report 失败"
          description={`离线报表接口暂不可用:${toApiError(abQuery.error).message}。`}
        />
      ) : !data || data.file === null || rows.length === 0 ? (
        <EmptyReport />
      ) : (
        <Ready
          rows={rows}
          hasSig={hasSig}
          sortedRows={sortedRows}
          filtered={filtered}
          view={view}
          setView={setView}
          sortKey={sortKey}
          setSortKey={setSortKey}
          onlySig={onlySig}
          setOnlySig={setOnlySig}
          expQuery={expQuery}
          onTuneTraffic={() => navigate('/experiment')}
        />
      )}
    </Space>
  );
}

// —— 加载态:各段独立骨架,不整页 Spin(§7)。 ——
function LoadingBody() {
  return (
    <>
      <div className="skl" style={{ height: 64, borderRadius: 8 }} />
      <StatCardsSkeleton count={4} />
      <ChartSkeleton height={360} />
      <ChartSkeleton height={240} />
    </>
  );
}

// —— 暂无报表态:EmptyState + 生成命令(§7)。 ——
function EmptyReport() {
  return (
    <Card>
      <EmptyState
        accent={ACCENTS.rank}
        icon={<ExperimentOutlined />}
        title="暂无 ab-report"
        description="还没有带 bucket 的在线分桶 CTR 报表。先跑离线作业生成(需线上已有带 bucket 标记的 IMPRESSION 曝光埋点):"
        action={
          <Typography.Paragraph copyable className="mono" style={{ marginBottom: 0 }}>
            mvn -pl recsys-offline spring-boot:run -Dspring-boot.run.arguments=--job=ab-report
          </Typography.Paragraph>
        }
      />
    </Card>
  );
}

interface ReadyProps {
  rows: AbBucketRow[];
  hasSig: boolean;
  sortedRows: AbBucketRow[];
  filtered: AbBucketRow[];
  view: ViewKey;
  setView: (v: ViewKey) => void;
  sortKey: SortKey;
  setSortKey: (v: SortKey) => void;
  onlySig: boolean;
  setOnlySig: (v: boolean) => void;
  expQuery: UseQueryResult<ExperimentSnapshot, Error>;
  onTuneTraffic: () => void;
}

function Ready(props: ReadyProps) {
  const {
    rows,
    hasSig,
    sortedRows,
    filtered,
    view,
    setView,
    sortKey,
    setSortKey,
    onlySig,
    setOnlySig,
    expQuery,
    onTuneTraffic,
  } = props;

  const baseline = rows.find((r) => r.isBaseline);
  const sig = significantBuckets(rows);
  const nonBaseline = rows.filter((r) => !r.isBaseline);
  const winners = sig.filter((r) => Number.isFinite(r.lift) && r.lift > 0); // 显著且正向
  const maxLift = Math.max(
    0,
    ...rows.filter((r) => !r.isBaseline && Number.isFinite(r.lift)).map((r) => r.lift),
  );

  // 仅显著开启后仅剩基线 → 图/表上方灰提示(§8)。
  const onlyBaselineLeft = onlySig && filtered.length > 0 && filtered.every((r) => r.isBaseline);

  return (
    <>
      {/* ② 结论条 Verdict Bar —— 全页记忆点,0 点击可读。 */}
      <VerdictBar
        hasSig={hasSig}
        sig={sig}
        winners={winners}
        maxLift={maxLift}
        onTuneTraffic={onTuneTraffic}
      />

      {/* ③ KPI 行(§3)。 */}
      <KpiRow
        rows={rows}
        hasSig={hasSig}
        baseline={baseline}
        sig={sig}
        nonBaseline={nonBaseline}
        maxLift={maxLift}
      />

      {/* ④ 对比区:控制条 + 视图 A(完整分桶) / 视图 B(层×变体汇总)。 */}
      <Card size="small">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Space wrap size={[16, 8]}>
            <Segmented<ViewKey>
              value={view}
              onChange={(v) => setView(v)}
              options={['完整分桶', '层×变体汇总']}
            />
            {view === '完整分桶' ? (
              <>
                <Space size={8}>
                  <Typography.Text type="secondary">排序</Typography.Text>
                  <Segmented<SortKey>
                    value={sortKey}
                    onChange={(v) => setSortKey(v)}
                    options={['原始', 'CTR', 'lift', 'p 值', '曝光']}
                  />
                </Space>
                <Space size={8}>
                  <Switch checked={onlySig} onChange={setOnlySig} />
                  <Typography.Text type="secondary">仅显著</Typography.Text>
                </Space>
              </>
            ) : null}
          </Space>

          {!hasSig ? (
            <Typography.Text type="secondary">旧格式:无 CI/显著性列</Typography.Text>
          ) : null}

          {view === '完整分桶' ? (
            <>
              {onlyBaselineLeft ? (
                <Typography.Text type="secondary">当前无显著桶,仅显示基线</Typography.Text>
              ) : null}
              <FullBucketChart rows={sortedRows} hasSig={hasSig} baseline={baseline} />
            </>
          ) : (
            <VariantSummary rows={rows} expQuery={expQuery} />
          )}
        </Space>
      </Card>

      {/* ⑤ 明细大表(§5)。 */}
      <Card size="small">
        <DetailTable rows={sortedRows} hasSig={hasSig} />
      </Card>
    </>
  );
}

// —— ② 结论条:纯 rows 派生的三态 + 旧格式旁支(§2)。 ——
function VerdictBar({
  hasSig,
  sig,
  winners,
  maxLift,
  onTuneTraffic,
}: {
  hasSig: boolean;
  sig: AbBucketRow[];
  winners: AbBucketRow[];
  maxLift: number;
  onTuneTraffic: () => void;
}) {
  const kind = !hasSig ? 'OLD' : sig.length === 0 ? 'FLAT' : winners.length > 0 ? 'WIN' : 'REVIEW';

  // 描述里的桶名:mono,多个用「、」连接(照抄 AbSignificancePanel:125)。
  const names = (list: AbBucketRow[]): ReactNode =>
    list.map((r, i) => (
      <span key={r.bucket}>
        {i > 0 ? '、' : ''}
        <span className="mono">{r.bucket}</span>
      </span>
    ));

  const maxLiftStr = (maxLift * 100).toFixed(2);

  let antdType: 'success' | 'warning' | 'info' = 'info';
  let icon: ReactNode = <InfoCircleOutlined />;
  let title = '';
  let detail: ReactNode = null;

  if (kind === 'WIN') {
    antdType = 'success';
    icon = <TrophyOutlined />;
    title = `有 ${winners.length} 个桶显著优于基线`;
    detail = (
      <span>
        胜出桶:{names(winners)}。最大正向 lift +{maxLiftStr}%。若其中含与基线同策略的桶(AA),说明分流/埋点有偏,应先修分流再放量;若确为不同策略,则为真实实验效果。
      </span>
    );
  } else if (kind === 'FLAT') {
    antdType = 'info';
    icon = <InfoCircleOutlined />;
    title = '未发现显著差异';
    detail =
      '各桶 CTR 与基线的差异均不显著(可能效果为零,或样本量不足——见明细「最小样本/臂」)。暂不建议据此放量。';
  } else if (kind === 'REVIEW') {
    antdType = 'warning';
    icon = <WarningOutlined />;
    title = '检测到显著差异,但无桶优于基线,需复核';
    detail = (
      <span>
        有 {sig.length} 个桶与基线差异显著却无正向 lift({names(sig)})。优先排查:①是否存在与基线同策略的桶(AA)→ 分流/埋点有偏;②该变体确实劣于基线。修分流前不要下正式结论。
      </span>
    );
  } else {
    // OLD
    antdType = 'info';
    icon = <InfoCircleOutlined />;
    title = '旧格式报表';
    detail =
      '本份 ab-report 无 CI / 显著性列,无法给出胜负结论,仅展示逐桶 CTR。重跑 --job=ab-report 可得完整结论。';
  }

  return (
    <Alert
      showIcon
      type={antdType}
      icon={icon}
      message={<strong>{title}</strong>}
      description={detail}
      action={
        <Button size="small" onClick={onTuneTraffic}>
          去调流量 →
        </Button>
      }
    />
  );
}

// —— ③ KPI 行:4 砖(§3)。 ——
function KpiRow({
  rows,
  hasSig,
  baseline,
  sig,
  nonBaseline,
  maxLift,
}: {
  rows: AbBucketRow[];
  hasSig: boolean;
  baseline?: AbBucketRow;
  sig: AbBucketRow[];
  nonBaseline: AbBucketRow[];
  maxLift: number;
}) {
  const baseCtrOk = baseline && Number.isFinite(baseline.ctr);

  return (
    <Row gutter={[16, 16]}>
      <Col xs={24} sm={12} lg={6}>
        <StatCard
          title="分桶数"
          value={rows.length}
          icon={<BranchesOutlined />}
          accent={ACCENTS.recall}
        />
      </Col>
      <Col xs={24} sm={12} lg={6}>
        <StatCard
          title="基线 CTR"
          value={baseCtrOk ? (baseline!.ctr * 100).toFixed(2) : '—'}
          suffix={baseCtrOk ? '%' : undefined}
          icon={<AimOutlined />}
          accent={ACCENTS.rank}
          valueColor={baseCtrOk ? undefined : GRAY}
        />
      </Col>
      <Col xs={24} sm={12} lg={6}>
        <StatCard
          title="显著桶数"
          value={hasSig ? sig.length : '—'}
          suffix={hasSig ? `/ ${nonBaseline.length}` : undefined}
          icon={<StarOutlined />}
          accent={ACCENTS.rerank}
          valueColor={hasSig ? (sig.length > 0 ? GREEN : undefined) : GRAY}
        />
      </Col>
      <Col xs={24} sm={12} lg={6}>
        <StatCard
          title="最大正向 lift"
          value={hasSig ? (maxLift > 0 ? '+' + (maxLift * 100).toFixed(2) : '—') : '—'}
          suffix={hasSig && maxLift > 0 ? '%' : undefined}
          icon={<RiseOutlined />}
          accent={ACCENTS.gsp}
          valueColor={hasSig && maxLift > 0 ? GREEN : GRAY}
        />
      </Col>
    </Row>
  );
}

// —— ④ 视图 A:完整分桶误差棒图(§4)。 ——
function FullBucketChart({
  rows,
  hasSig,
  baseline,
}: {
  rows: AbBucketRow[];
  hasSig: boolean;
  baseline?: AbBucketRow;
}) {
  const cats = rows.map((r) => r.bucket);
  const values = rows.map((r) => pct(r.ctr));
  const low = rows.map((r) => (hasSig && Number.isFinite(r.ciLow) ? pct(r.ciLow) : pct(r.ctr)));
  const high = rows.map((r) => (hasSig && Number.isFinite(r.ciHigh) ? pct(r.ciHigh) : pct(r.ctr)));
  const markers = rows.map((r) => (r.isBaseline ? '基线' : r.significant === true ? '★ 显著' : null));
  const baseIdx = rows.findIndex((r) => r.isBaseline);

  return (
    <EErrorBar
      categories={cats}
      values={values}
      low={low}
      high={high}
      markers={markers}
      barName="CTR%"
      yName="%"
      height={360}
      colorIndex={1}
      baselineValue={baseline ? pct(baseline.ctr) : undefined}
      highlightIndex={baseIdx >= 0 ? baseIdx : undefined}
      highlightColor={ACCENTS.rank}
      fileName="bucket-board-ctr.png"
    />
  );
}

interface LayerRow {
  key: string;
  variant: string;
  st: VariantOnlineStat | null;
}

// —— ⑥ 视图 B:层×变体边际汇总,每层一张 CollapsibleCard(§6)。取数独立降级。 ——
function VariantSummary({
  rows,
  expQuery,
}: {
  rows: AbBucketRow[];
  expQuery: ReadyProps['expQuery'];
}) {
  if (expQuery.isError) {
    return (
      <Alert
        type="warning"
        showIcon
        message="实验配置不可达,层×变体汇总暂不可用"
        description={toApiError(expQuery.error).message}
      />
    );
  }
  if (expQuery.isLoading) return <ChartSkeleton height={240} />;
  const snap = expQuery.data;
  if (!snap) return null;

  const layers = Object.entries(snap.staticLayers);

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {layers.map(([layer, cfg]) => {
        const variants = Object.keys(cfg.variants);
        if (variants.length === 0) return null; // 层 config 缺失/空 → 不渲染,不报错(§6)。
        const layerRows: LayerRow[] = variants.map((variant) => ({
          key: variant,
          variant,
          st: variantOnlineStat(rows, layer, variant),
        }));

        return (
          <CollapsibleCard
            key={layer}
            accent={ACCENTS.rerank}
            icon={<PartitionOutlined />}
            title={
              <>
                层:<span className="mono">{layer}</span>
              </>
            }
            extra={
              cfg.salt ? (
                <Typography.Text type="secondary" className="mono" style={{ fontSize: 12 }}>
                  salt={cfg.salt}
                </Typography.Text>
              ) : undefined
            }
          >
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {variants.length > 1 ? (
                <EBar
                  categories={variants}
                  series={[
                    {
                      name: '合并 CTR%',
                      data: layerRows.map((r) =>
                        r.st && Number.isFinite(r.st.ctr) ? +(r.st.ctr * 100).toFixed(2) : 0,
                      ),
                    },
                  ]}
                  height={220}
                  yName="%"
                />
              ) : null}
              <Table<LayerRow>
                size="small"
                rowKey="key"
                pagination={false}
                dataSource={layerRows}
                scroll={{ x: 'max-content' }}
                columns={[
                  {
                    title: '变体',
                    dataIndex: 'variant',
                    render: (v: string) => (
                      <Tag className="mono" color={channelColor(v)}>
                        {v}
                      </Tag>
                    ),
                  },
                  {
                    title: '合并 CTR',
                    key: 'ctr',
                    align: 'right',
                    render: (_: unknown, r) =>
                      r.st == null ? (
                        <Typography.Text type="secondary">无匹配曝光</Typography.Text>
                      ) : (
                        <span className="mono">
                          {Number.isFinite(r.st.ctr) ? (r.st.ctr * 100).toFixed(2) + '%' : 'n/a'}
                        </span>
                      ),
                  },
                  {
                    title: '命中桶数',
                    key: 'buckets',
                    align: 'right',
                    render: (_: unknown, r) => <span className="mono">{r.st?.buckets ?? '-'}</span>,
                  },
                  {
                    title: '合计曝光',
                    key: 'impressions',
                    align: 'right',
                    render: (_: unknown, r) => (
                      <span className="mono">{r.st?.impressions ?? '-'}</span>
                    ),
                  },
                  {
                    title: '含显著桶?',
                    key: 'anySig',
                    align: 'center',
                    render: (_: unknown, r) =>
                      r.st == null ? (
                        <Typography.Text type="secondary">-</Typography.Text>
                      ) : r.st.anySignificant ? (
                        <Tag color="green">含显著桶</Tag>
                      ) : (
                        <Tag>无</Tag>
                      ),
                  },
                ]}
              />
            </Space>
          </CollapsibleCard>
        );
      })}
    </Space>
  );
}

// —— ⑤ 明细大表:列渲染照抄 AbSignificancePanel + 列头排序 + 基线行高亮 + 样本不足标(§5)。 ——
function DetailTable({ rows, hasSig }: { rows: AbBucketRow[]; hasSig: boolean }) {
  // 数值列排序工厂:基线/NaN 恒沉底,不受升降序影响(§5)。
  const numSorter = (key: keyof AbBucketRow) => (a: AbBucketRow, b: AbBucketRow) => {
    const bad = (r: AbBucketRow) => r.isBaseline || !Number.isFinite(r[key] as number);
    if (bad(a) && bad(b)) return 0;
    if (bad(a)) return 1;
    if (bad(b)) return -1;
    return (a[key] as number) - (b[key] as number);
  };

  const allColumns: TableColumnsType<AbBucketRow> = [
    {
      title: '分桶',
      key: 'bucket',
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
    {
      title: '曝光',
      key: 'impressions',
      dataIndex: 'impressions',
      align: 'right',
      render: (n: number) => (Number.isFinite(n) ? n : '-'),
      sorter: numSorter('impressions'),
      sortDirections: ['descend', 'ascend'],
    },
    {
      title: '点击',
      key: 'clicks',
      dataIndex: 'clicks',
      align: 'right',
      render: (n: number) => (Number.isFinite(n) ? n : '-'),
    },
    {
      title: 'CTR',
      key: 'ctr',
      dataIndex: 'ctr',
      align: 'right',
      render: (n: number) => pctStr(n),
      sorter: numSorter('ctr'),
      sortDirections: ['descend', 'ascend'],
    },
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
      key: 'lift',
      dataIndex: 'lift',
      align: 'right',
      render: (n: number, r) =>
        r.isBaseline || !Number.isFinite(n) ? (
          <Typography.Text type="secondary">{r.isBaseline ? '(基线)' : '-'}</Typography.Text>
        ) : (
          <span style={{ color: n >= 0 ? GREEN : RED }}>
            {(n >= 0 ? '+' : '') + (n * 100).toFixed(2)}%
          </span>
        ),
      sorter: numSorter('lift'),
      sortDirections: ['descend', 'ascend'],
    },
    {
      title: 'p 值',
      key: 'pValue',
      dataIndex: 'pValue',
      align: 'right',
      render: (n: number, r) =>
        r.isBaseline || !Number.isFinite(n) ? (
          <Typography.Text type="secondary">-</Typography.Text>
        ) : (
          <span className="mono">{n < 0.0001 ? '<1e-4' : n.toFixed(4)}</span>
        ),
      sorter: numSorter('pValue'),
      sortDirections: ['descend', 'ascend'],
    },
    {
      title: '显著',
      key: 'significant',
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
      key: 'minSample',
      dataIndex: 'minSample',
      align: 'right',
      render: (n: number, r) =>
        r.isBaseline ? (
          <Typography.Text type="secondary">-</Typography.Text>
        ) : r.minSampleInf ? (
          <Tag color="orange">∞</Tag>
        ) : Number.isFinite(n) ? (
          <Space size={4}>
            <span className="mono">{n}</span>
            {Number.isFinite(r.impressions) && r.impressions < n ? (
              <Tag color="gold">样本不足</Tag>
            ) : null}
          </Space>
        ) : (
          <Typography.Text type="secondary">-</Typography.Text>
        ),
      sorter: numSorter('minSample'),
      sortDirections: ['descend', 'ascend'],
    },
  ];

  // 旧格式(无显著性列):隐藏 95%CI / lift / p值 / 显著 / 最小样本/臂 五列(§7)。
  const hiddenWhenOld = ['ci', 'lift', 'pValue', 'significant', 'minSample'];
  const columns = allColumns.filter((c) => hasSig || !hiddenWhenOld.includes(String(c.key)));

  return (
    <Table<AbBucketRow>
      size="small"
      rowKey="bucket"
      pagination={false}
      dataSource={rows}
      scroll={{ x: 'max-content' }}
      rowClassName={(r) => (r.isBaseline ? 'bb-baseline-row' : '')}
      columns={columns}
    />
  );
}
