import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DeliveryState,
  PreparationStatus,
  getSandboxDelivery,
  putSandboxDelivery,
  type SandboxAdItem,
  type SandboxDeliveryInput,
  type SandboxDeliveryView,
} from '../../api/exposure';
import { toApiError } from '../../api/client';
import { useAuth } from '../../hooks/useAuth';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import { useRequestHistory, type HistoryEntry } from '../../hooks/useRequestHistory';
import { useVisibleExposure, type MeasurementPhase } from '../../hooks/useVisibleExposure';
import ExposureScope, { useExposureScope } from '../../components/exposure/ExposureScope';
import ReceiptStatus from '../../components/exposure/ReceiptStatus';
import { receiptQueue } from '../../exposure/receiptQueue';
import { exposureEpoch, onExposureRevoked } from '../../exposure/identity';
import PageHeader from '../../components/PageHeader';
import HistoryDrawer from '../../components/debug/HistoryDrawer';
import ResultDiff from '../../components/debug/ResultDiff';
import TracePanel from '../../components/explain/TracePanel';
import { ACCENTS } from '../../theme/tokens';

const SESSION_KEY = 'recsys.sandbox.delivery.v1';
interface Intent {
  operationId: string;
  input: SandboxDeliveryInput;
  createdAt: number;
  scope: string;
}
function Creative({
  item,
  enabled,
  status,
}: {
  item: SandboxAdItem;
  enabled: boolean;
  status: (value: MeasurementPhase) => void;
}) {
  const preparation = item.preparation;
  const { ref, phase } = useVisibleExposure(
    enabled && !!item.title?.trim() && preparation?.status === PreparationStatus.QUALIFIED,
    preparation?.expiresAt ?? null,
    (duration, ratio) =>
      !!preparation?.qualificationToken &&
      !!receiptQueue().enqueue(item.childId, preparation.expiresAt!, {
        qualificationToken: preparation.qualificationToken,
        visibleDurationMs: duration,
        minVisibleRatioBps: ratio,
        pageVisible: true,
      }),
  );
  useEffect(() => status(item.title?.trim() ? phase : 'unavailable'), [phase, status, item.title]);
  return (
    <div>
      <div
        ref={ref}
        data-testid={`creative-${item.slot}`}
        style={{
          minHeight: 84,
          padding: 16,
          border: '1px solid #d9e2f2',
          borderRadius: 8,
          background: '#fff',
        }}
      >
        <Typography.Text strong>{item.title || '创意内容缺失'}</Typography.Text>
      </div>
      <Typography.Text type="secondary" style={{ fontSize: 11 }}>
        广告 {item.adId} · 创意 {item.creativeId}
      </Typography.Text>
    </div>
  );
}
function SandboxPage() {
  const identity = useExposureScope();
  const { user } = useAuth();
  const global = useGlobalUser();
  const [form] = Form.useForm<SandboxDeliveryInput>();
  const [intent, setIntent] = useState<Intent | null>(null);
  const [delivery, setDelivery] = useState<SandboxDeliveryView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [compareOpen, setCompareOpen] = useState(false);
  const [phases, setPhases] = useState<Record<string, MeasurementPhase>>({});
  const history = useRequestHistory<SandboxDeliveryInput, SandboxDeliveryView>();
  const request = useRef<AbortController | null>(null);
  const activeIdentity = useRef(identity);
  activeIdentity.current = identity;
  const measurementCallbacks = useRef(new Map<string, (phase: MeasurementPhase) => void>());
  const callback = (id: string) => {
    if (!measurementCallbacks.current.has(id))
      measurementCallbacks.current.set(id, (phase) =>
        setPhases((old) => (old[id] === phase ? old : { ...old, [id]: phase })),
      );
    return measurementCallbacks.current.get(id)!;
  };
  const persist = (value: Intent) => {
    try {
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(value));
    } catch {
      setError('当前交付无法写入会话存储，请保留操作 ID；刷新后无法自动恢复。');
    }
  };
  const execute = async (value: Intent, queryOnly = false) => {
    const owner = activeIdentity.current;
    if (!owner || owner.scope !== value.scope || owner.epoch !== exposureEpoch()) return;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setError('');
    const start = performance.now();
    try {
      const result = queryOnly
        ? await getSandboxDelivery(value.operationId, owner, controller.signal)
        : await putSandboxDelivery(value.operationId, value.input, owner, controller.signal);
      if (
        controller.signal.aborted ||
        activeIdentity.current?.scope !== owner.scope ||
        exposureEpoch() !== owner.epoch
      )
        return;
      if (
        result.operationId !== value.operationId ||
        result.userId !== value.input.userId ||
        result.trafficClass !== 'SANDBOX'
      )
        throw new Error('交付响应身份不匹配');
      setDelivery(result);
      if (result.state === DeliveryState.COMPLETE)
        history.push(value.input, result, Math.round(performance.now() - start));
    } catch (e) {
      if (
        !controller.signal.aborted &&
        activeIdentity.current?.scope === owner.scope &&
        exposureEpoch() === owner.epoch
      )
        setError(toApiError(e).message);
    } finally {
      if (request.current === controller) {
        request.current = null;
        setBusy(false);
      }
    }
  };
  useEffect(() => {
    const clear = () => {
      request.current?.abort();
      setBusy(false);
      setIntent(null);
      setDelivery(null);
      setPhases({});
      history.clear();
      measurementCallbacks.current.clear();
    };
    clear();
    if (identity) {
      try {
        const raw = sessionStorage.getItem(SESSION_KEY);
        if (raw && raw.length < 8192) {
          const saved = JSON.parse(raw) as Intent;
          if (
            saved.scope === identity.scope &&
            Number.isFinite(saved.createdAt) &&
            saved.createdAt <= Date.now() &&
            Date.now() - saved.createdAt < 3600000 &&
            /^[0-9a-f-]{36}$/.test(saved.operationId) &&
            typeof saved.input?.userId === 'string' &&
            typeof saved.input.query === 'string'
          ) {
            setIntent(saved);
            form.setFieldsValue(saved.input);
            void execute(saved, true);
          } else sessionStorage.removeItem(SESSION_KEY);
        }
      } catch {
        /* 存储不可用时仍可主动发起，不能构造假历史。 */
      }
    }
    const unsubscribe = onExposureRevoked(clear);
    return () => {
      unsubscribe();
      request.current?.abort();
    };
    // 身份变化是唯一恢复边界，execute/history 的变化不触发新的查询或交付。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [identity?.scope, identity?.epoch]);
  const start = (input: SandboxDeliveryInput) => {
    if (!identity || busy || (intent && delivery?.state !== DeliveryState.COMPLETE)) return;
    const value: Intent = {
      operationId: crypto.randomUUID(),
      input: { ...input },
      createdAt: Date.now(),
      scope: identity.scope,
    };
    setIntent(value);
    setDelivery(null);
    setPhases({});
    measurementCallbacks.current.clear();
    persist(value);
    void execute(value);
  };
  const rerun = (entry: HistoryEntry<SandboxDeliveryInput, SandboxDeliveryView>) => {
    form.setFieldsValue(entry.params);
    setDrawerOpen(false);
    start(entry.params);
  };
  const selected = history.entries.filter((entry) => history.selected.includes(entry.id));
  const pending = !!intent && delivery?.state !== DeliveryState.COMPLETE;
  const columns: ColumnsType<SandboxAdItem> = [
    { title: '位置', dataIndex: 'slot', width: 64 },
    {
      title: '创意',
      key: 'creative',
      width: 300,
      render: (_, item) => (
        <Creative
          item={item}
          enabled={
            !!identity && !drawerOpen && !compareOpen && delivery?.state === DeliveryState.COMPLETE
          }
          status={callback(item.childId)}
        />
      ),
    },
    {
      title: '准备 / 报价',
      key: 'preparation',
      width: 150,
      render: (_, item) => (
        <Space direction="vertical" size={4}>
          <Tag>
            {item.preparation?.status === PreparationStatus.QUALIFIED
              ? '资格已准备'
              : (item.preparation?.status ?? '准备中')}
          </Tag>
          <Typography.Text className="mono">
            {item.preparation?.status === PreparationStatus.QUALIFIED
              ? `¥${item.preparation.amount} / 次曝光`
              : '—'}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '回执 / 消费',
      key: 'receipt',
      width: 240,
      render: (_, item) =>
        item.preparation?.status === PreparationStatus.QUALIFIED ? (
          <ReceiptStatus childId={item.childId} measurement={phases[item.childId] ?? 'waiting'} />
        ) : (
          <Typography.Text type="secondary">无可计费资格</Typography.Text>
        ),
    },
  ];
  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="搜索广告 · Sandbox"
        accent={ACCENTS.ad}
        description="图文创意至少 50% 连续可见 1 秒后提交回执；金额以服务端确认结果为准。"
        extra={<Button onClick={() => setDrawerOpen(true)}>请求历史</Button>}
      />
      <Alert
        type="info"
        showIcon
        message="仅测试账户"
        description="资格有效期五分钟。离屏或切到后台会重新计时；关闭页面前未提交的观测可能无法恢复。"
      />
      {!user?.roles.includes('ADMIN') && (
        <Alert type="warning" showIcon message="当前身份需要 ADMIN 权限" />
      )}
      <Card size="small">
        <Form
          form={form}
          layout="vertical"
          onFinish={start}
          initialValues={{
            userId: String(global.userId),
            query: 'action',
            slots: 3,
            scene: global.scene,
          }}
        >
          <Space align="start" wrap>
            <Form.Item
              name="userId"
              label="模拟用户 ID"
              rules={[
                {
                  required: true,
                  pattern: /^[1-9][0-9]{0,18}$/,
                  message: '请输入正整数 ID（字符串）',
                },
              ]}
            >
              <Input style={{ width: 190 }} maxLength={19} />
            </Form.Item>
            <Form.Item
              name="query"
              label="搜索词"
              rules={[
                { required: true, whitespace: true, max: 500, message: '请输入 1～500 字搜索词' },
              ]}
            >
              <Input style={{ width: 230 }} maxLength={500} />
            </Form.Item>
            <Form.Item name="slots" label="广告位" rules={[{ required: true }]}>
              <InputNumber min={1} max={10} precision={0} />
            </Form.Item>
            <Form.Item
              name="scene"
              label="场景"
              rules={[{ required: true, whitespace: true, max: 100 }]}
            >
              <Input style={{ width: 150 }} maxLength={100} />
            </Form.Item>
          </Space>
          <Space wrap>
            <Button
              type="primary"
              htmlType="submit"
              aria-label="新交付"
              aria-busy={busy}
              loading={busy}
              disabled={!identity || pending}
            >
              新交付
            </Button>
            {intent && (
              <Button
                aria-label="恢复原操作"
                aria-busy={busy}
                loading={busy}
                disabled={!identity}
                onClick={() => execute(intent)}
              >
                恢复原操作
              </Button>
            )}
            {pending && (
              <Typography.Text type="secondary">
                原操作尚未确认完成，请先恢复；编辑表单不会改变原请求。
              </Typography.Text>
            )}
          </Space>
        </Form>
      </Card>
      {error && <Alert type="warning" showIcon message="交付结果尚未确认" description={error} />}
      {intent && (
        <Typography.Text type="secondary" style={{ overflowWrap: 'anywhere' }}>
          操作 {intent.operationId} · 原用户 {intent.input.userId}
        </Typography.Text>
      )}
      {delivery && (
        <Table<SandboxAdItem>
          size="small"
          rowKey="childId"
          columns={columns}
          dataSource={delivery.items}
          pagination={false}
          scroll={{ x: 754 }}
          locale={{
            emptyText:
              delivery.state === DeliveryState.PREPARING
                ? '操作准备中，请恢复原操作'
                : '本次没有可交付广告',
          }}
        />
      )}
      {delivery && <TracePanel requestId={delivery.operationId} raw={delivery} />}
      <HistoryDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        entries={history.entries}
        selected={history.selected}
        onToggleSelect={history.toggleSelect}
        onRerun={rerun}
        onCompare={() => setCompareOpen(true)}
        onClear={history.clear}
        renderSummary={(entry) => (
          <Typography.Text>
            {entry.params.query} · 原用户 {entry.params.userId} · {entry.data.items.length} 条
          </Typography.Text>
        )}
      />
      <Modal
        title="交付对比（只读）"
        open={compareOpen}
        onCancel={() => setCompareOpen(false)}
        footer={null}
        width={760}
      >
        {selected.length === 2 && (
          <ResultDiff
            aLabel={selected[0].params.query}
            bLabel={selected[1].params.query}
            aItems={selected[0].data.items}
            bItems={selected[1].data.items}
            keyOf={(item) => item.adId}
            primary={(item) => item.title}
            columns={[]}
          />
        )}
      </Modal>
    </Space>
  );
}
export default function SandboxSearchAdsConsole() {
  return (
    <ExposureScope>
      <SandboxPage />
    </ExposureScope>
  );
}
