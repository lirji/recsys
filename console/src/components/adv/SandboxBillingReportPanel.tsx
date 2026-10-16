import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Input,
  Row,
  Skeleton,
  Space,
  Table,
  Tag,
  Typography,
  type TableProps,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import {
  FUNDS_STATUS,
  sandboxReconciliation,
  validBudgetDate,
  type BillingFamilyView,
  type ProjectionStatus,
} from '../../api/sandboxReconciliation';
import type { ExposureIdentity } from '../../api/exposure';
import { toApiError } from '../../api/client';

const familyLabels = { IMPRESSION: 'CPM 展示', CLICK: 'CPC 点击', CONVERSION: 'CPA 转化' };
const projectionLabels: Record<ProjectionStatus, string> = {
  CAUGHT_UP: '已追平',
  LAGGING: '尚未追平',
  MISMATCH: '存在差异',
};
const numeric = { fontVariantNumeric: 'tabular-nums' } as const;
/** 原预算日以业务时区生成，用户提交后不随浏览器本地时区或刷新改变。 */
function currentBudgetDate() {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date());
  return ['year', 'month', 'day'].map((key) => parts.find((p) => p.type === key)?.value).join('-');
}
function ProjectionTag({ value }: { value: ProjectionStatus }) {
  return (
    <Tag color={value === 'CAUGHT_UP' ? 'success' : value === 'LAGGING' ? 'processing' : 'error'}>
      {projectionLabels[value]}
    </Tag>
  );
}
function FamilyDetails({ value: f }: { value: BillingFamilyView }) {
  const items = [
    ['有效预占笔数', f.heldCount],
    ['有效预占金额（CNY）', f.heldAmount],
    ['到期未释放笔数', f.expiredHeldCount],
    ['缺失账本笔数', f.missingLedgerCount],
    ['账本身份不匹配笔数', f.invalidLedgerCount],
    ['缺失 Outbox 笔数', f.missingOutboxCount],
    ['最早待发创建时间', f.oldestPendingAt ?? '无待发记录'],
    ['缺失投影事实笔数', f.missingFactCount],
    ['投影身份不匹配笔数', f.invalidFactCount],
    ['额外投影事实笔数', f.unexpectedFactCount],
    ['日汇总行', f.summaryPresent ? '存在' : '不存在'],
    ['日汇总笔数', f.summaryCount],
    ['日汇总金额（CNY）', f.summaryAmount],
  ];
  return (
    <Descriptions
      size="small"
      column={{ xs: 1, sm: 2, lg: 3 }}
      items={items.map(([label, value]) => ({
        key: label,
        label,
        children: <span style={{ ...numeric, overflowWrap: 'anywhere' }}>{value}</span>,
      }))}
    />
  );
}
const columns: TableProps<BillingFamilyView>['columns'] = [
  {
    title: '计费类型',
    dataIndex: 'family',
    width: 132,
    render: (family: BillingFamilyView['family']) => familyLabels[family],
  },
  { title: '账本笔数', dataIndex: 'ledgerCount', width: 104, align: 'right' },
  { title: '账本金额（CNY）', dataIndex: 'ledgerAmount', width: 170, align: 'right' },
  {
    title: '传输状态',
    width: 150,
    render: (_, f) => (
      <Space direction="vertical" size={0}>
        <span>待发 {f.pendingOutboxCount}</span>
        <span>DEAD {f.deadOutboxCount}</span>
      </Space>
    ),
  },
  { title: '投影笔数', dataIndex: 'factCount', width: 104, align: 'right' },
  { title: '投影金额（CNY）', dataIndex: 'factAmount', width: 170, align: 'right' },
  {
    title: '投影核对',
    dataIndex: 'projectionStatus',
    width: 120,
    render: (value: ProjectionStatus) => <ProjectionTag value={value} />,
  },
  {
    title: '投影最后应用时间（UTC）',
    dataIndex: 'lastAppliedAt',
    width: 250,
    render: (value: string | null) => value ?? '尚无投影记录',
  },
];

/** 只读快照独立于旧事件统计，范围切换及刷新期间不展示旧资金值。 */
export default function SandboxBillingReportPanel({
  advertiserId,
  identity,
}: {
  advertiserId: string;
  identity: ExposureIdentity | null;
}) {
  const [params, setParams] = useSearchParams();
  const [today] = useState(currentBudgetDate);
  const selected = params.get('budgetDate') ?? today;
  const [draft, setDraft] = useState(selected);
  const [invalidDraft, setInvalidDraft] = useState(false);
  const queryClient = useQueryClient();
  const queryKey = useMemo(
    () => ['sandbox-reconciliation', identity?.scope, identity?.epoch, advertiserId, selected],
    [identity?.scope, identity?.epoch, advertiserId, selected],
  );
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => sandboxReconciliation(advertiserId, selected, identity!, signal),
    enabled: !!identity && validBudgetDate(selected),
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  useEffect(() => {
    setDraft(selected);
    setInvalidDraft(false);
  }, [selected]);
  useEffect(() => {
    if (query.isError && [401, 403].includes(toApiError(query.error).status ?? 0)) {
      queryClient.removeQueries({ queryKey, exact: true });
    }
  }, [query.isError, query.error, queryKey, queryClient]);
  const data = identity && query.isSuccess && !query.isFetching ? query.data : undefined;
  function submit(event: FormEvent) {
    event.preventDefault();
    if (!validBudgetDate(draft)) {
      setInvalidDraft(true);
      return;
    }
    setInvalidDraft(false);
    if (!identity) return;
    if (draft === selected) void query.refetch();
    else {
      const next = new URLSearchParams(params);
      next.set('budgetDate', draft);
      setParams(next);
    }
  }
  return (
    <Card title="Sandbox 账户日资金" styles={{ body: { minWidth: 0 } }}>
      <Space direction="vertical" size={16} style={{ width: '100%', minWidth: 0 }}>
        <form onSubmit={submit} aria-label="查询账户日资金">
          <Space wrap align="start">
            <div>
              <label htmlFor="billing-budget-date" style={{ display: 'block', marginBottom: 6 }}>
                原预算日（Asia/Shanghai）
              </label>
              <Input
                id="billing-budget-date"
                value={draft}
                placeholder="YYYY-MM-DD"
                autoComplete="off"
                style={{ width: 180 }}
                onChange={(e) => {
                  setDraft(e.target.value);
                  setInvalidDraft(false);
                }}
                status={invalidDraft ? 'error' : undefined}
                aria-invalid={invalidDraft}
                aria-describedby={invalidDraft ? 'billing-date-error' : undefined}
              />
              {invalidDraft && (
                <div id="billing-date-error" role="alert">
                  <Typography.Text type="danger">请输入有效日期，例如 2026-10-16</Typography.Text>
                </div>
              )}
            </div>
            <Space style={{ paddingTop: 28 }}>
              <Button
                type="primary"
                htmlType="submit"
                disabled={!identity}
                loading={query.isFetching}
              >
                查询
              </Button>
              <Button
                icon={<ReloadOutlined aria-hidden="true" />}
                disabled={!identity || !validBudgetDate(selected) || query.isFetching}
                onClick={() => void query.refetch()}
              >
                刷新快照
              </Button>
            </Space>
          </Space>
        </form>
        <Typography.Text type="secondary" style={{ overflowWrap: 'anywhere' }}>
          广告主 #{advertiserId} · 当前查询预算日 {selected} · SANDBOX · CNY
        </Typography.Text>
        {!identity ? (
          <Alert type="info" showIcon message="正在确认当前查看身份" />
        ) : !validBudgetDate(selected) ? (
          <Alert type="error" showIcon message="预算日无效，请输入有效日期后查询" />
        ) : query.isFetching || query.isPending ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : query.isError ? (
          <Alert
            type="error"
            showIcon
            message={toApiError(query.error).message}
            description="当前未展示资金快照。可保留预算日，稍后重新查询。"
          />
        ) : null}
        {data && (
          <>
            <Typography.Text type="secondary" style={{ overflowWrap: 'anywhere' }}>
              观察时间（UTC）：{data.observedAt}。这是本次一致快照的观察时间，不代表全局消息水位。
            </Typography.Text>
            <Alert
              showIcon
              type={
                data.fundsStatus === FUNDS_STATUS.BALANCED
                  ? 'success'
                  : data.fundsStatus === FUNDS_STATUS.MISMATCH
                    ? 'error'
                    : 'info'
              }
              message={
                data.fundsStatus === FUNDS_STATUS.BALANCED
                  ? '资金核对一致'
                  : data.fundsStatus === FUNDS_STATUS.MISMATCH
                    ? '资金链存在差异'
                    : '该预算日尚未建立账户'
              }
              description={
                data.account
                  ? `预占差额 ${data.heldDelta} CNY；消费差额 ${data.chargedDelta} CNY。差额 = 账户金额 − 对应预占明细或账本合计。`
                  : '账户金额和差额暂无值；下方仍展示该范围观察到的账本与投影事实。'
              }
            />
            {data.account && (
              <>
                <Row gutter={[16, 16]}>
                  {[
                    ['预算额度', data.account.limitAmount],
                    ['预占金额', data.account.heldAmount],
                    ['账户消费', data.account.chargedAmount],
                    ['可用额度', data.account.availableAmount],
                  ].map(([label, value]) => (
                    <Col xs={24} sm={12} xl={6} key={label}>
                      <div style={{ padding: '8px 0' }}>
                        <Typography.Text type="secondary">{label}（CNY）</Typography.Text>
                        <div
                          style={{
                            ...numeric,
                            fontSize: 20,
                            fontWeight: 600,
                            overflowWrap: 'anywhere',
                          }}
                        >
                          {value}
                        </div>
                      </div>
                    </Col>
                  ))}
                </Row>
                <Typography.Text type="secondary">
                  账户版本：{data.account.version}
                  。账户消费与下方账本逐笔核对，投影延迟不改变已确认的账户消费。
                </Typography.Text>
              </>
            )}
            <div style={{ width: '100%', minWidth: 0, ...numeric }}>
              <Table<BillingFamilyView>
                aria-label="三类资金事实核对"
                size="small"
                rowKey="family"
                dataSource={data.families}
                columns={columns}
                pagination={false}
                scroll={{ x: 1250 }}
                expandable={{
                  expandedRowRender: (row) => <FamilyDetails value={row} />,
                  columnWidth: 40,
                }}
              />
            </div>
            <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
              表格可左右滑动；展开各行可查看预占、到期未释放、缺失与不匹配笔数及日汇总。已发布不等于投影完成；投影最后应用时间仅说明已观察到的应用记录。
            </Typography.Paragraph>
          </>
        )}
      </Space>
    </Card>
  );
}
