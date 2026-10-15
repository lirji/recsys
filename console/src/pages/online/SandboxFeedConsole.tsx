import { useEffect, useRef, useState } from 'react';
import { ClockCircleOutlined, AppstoreOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Space, Typography } from 'antd';
import {
  AdDisposition,
  FeedEntryKind,
  getFeedDelivery,
  putFeedDelivery,
  validFeedInput,
  validateFeedDelivery,
  validateFeedProgress,
  type FeedDeliveryInput,
  type FeedDeliveryView,
} from '../../api/feedExposure';
import { positiveLong, naturalUuid } from '../../api/naturalExposure';
import { DeliveryState } from '../../api/exposure';
import { toApiError } from '../../api/client';
import { useAuth } from '../../hooks/useAuth';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import { useRequestHistory, type HistoryEntry } from '../../hooks/useRequestHistory';
import ExposureScope, { useExposureScope } from '../../components/exposure/ExposureScope';
import NaturalExposureCard from '../../components/exposure/NaturalExposureCard';
import { feedScopeQueue } from '../../exposure/feedScopeQueue';
import FeedAdCard from '../../components/exposure/FeedAdCard';
import { exposureEpoch, onExposureRevoked } from '../../exposure/identity';
import PageHeader from '../../components/PageHeader';
import EmptyState from '../../components/EmptyState';
import HistoryDrawer from '../../components/debug/HistoryDrawer';
import ResultDiff from '../../components/debug/ResultDiff';
import TracePanel from '../../components/explain/TracePanel';
import { ACCENTS } from '../../theme/tokens';

interface Intent {
  operationId: string;
  input: FeedDeliveryInput;
  createdAt: number;
  scope: string;
}
function FeedPage() {
  const identity = useExposureScope();
  const { user } = useAuth();
  const global = useGlobalUser();
  const sessionKey = 'recsys.sandbox.feed.v1';
  const [form] = Form.useForm<FeedDeliveryInput>();
  const [intent, setIntent] = useState<Intent | null>(null);
  const [delivery, setDelivery] = useState<FeedDeliveryView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [storageError, setStorageError] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [compareOpen, setCompareOpen] = useState(false);
  const history = useRequestHistory<FeedDeliveryInput, FeedDeliveryView>();
  const recorded = useRef<string | null>(null);
  const previous = useRef<FeedDeliveryView | null>(null);
  const request = useRef<AbortController | null>(null);
  const activeIdentity = useRef(identity);
  activeIdentity.current = identity;
  const execute = async (value: Intent, queryOnly = false) => {
    const owner = activeIdentity.current;
    if (!owner || owner.scope !== value.scope || owner.epoch !== exposureEpoch()) return;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setError('');
    const started = performance.now();
    const current = () =>
      !controller.signal.aborted &&
      activeIdentity.current?.scope === owner.scope &&
      exposureEpoch() === owner.epoch;
    try {
      const result = queryOnly
        ? await getFeedDelivery(value.operationId, owner, controller.signal)
        : await putFeedDelivery(value.operationId, value.input, owner, controller.signal);
      if (!current()) return;
      validateFeedDelivery(value.operationId, value.input, result);
      validateFeedProgress(previous.current, result);
      previous.current = result;
      setDelivery(result);
      if (result.state === DeliveryState.COMPLETE && recorded.current !== result.operationId) {
        history.push(value.input, result, Math.round(performance.now() - started));
        recorded.current = result.operationId;
      }
    } catch (e) {
      if (current()) setError(toApiError(e).message);
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
      setError('');
      setDrawerOpen(false);
      setCompareOpen(false);
      history.clear();
      recorded.current = null;
      previous.current = null;
    };
    clear();
    if (identity) {
      try {
        const raw = sessionStorage.getItem(sessionKey);
        if (raw && new TextEncoder().encode(raw).length <= 8192) {
          const saved = JSON.parse(raw) as Intent;
          if (
            saved.scope === identity.scope &&
            Number.isFinite(saved.createdAt) &&
            saved.createdAt <= Date.now() &&
            Date.now() - saved.createdAt < 3600000 &&
            naturalUuid(saved.operationId) &&
            validFeedInput(saved.input)
          ) {
            setIntent(saved);
            form.setFieldsValue(saved.input);
            void execute(saved, true);
          } else sessionStorage.removeItem(sessionKey);
        } else if (raw) sessionStorage.removeItem(sessionKey);
      } catch {
        setStorageError(true);
      }
    }
    const unsubscribe = onExposureRevoked(clear);
    return () => {
      unsubscribe();
      request.current?.abort();
    };
    // 只在真实身份/路由变化时恢复；表单和 history 变化不触发请求。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [identity?.scope, identity?.epoch, sessionKey]);
  const pending = !!intent && delivery?.state !== DeliveryState.COMPLETE;
  const start = (input: FeedDeliveryInput) => {
    if (!identity || busy || pending || !validFeedInput(input)) return;
    const value = {
      operationId: crypto.randomUUID(),
      input: { ...input },
      createdAt: Date.now(),
      scope: identity.scope,
    };
    setIntent(value);
    setDelivery(null);
    previous.current = null;
    try {
      sessionStorage.setItem(sessionKey, JSON.stringify(value));
    } catch {
      setStorageError(true);
    }
    void execute(value);
  };
  const submit = (values: FeedDeliveryInput) => {
    const query = values.query?.trim() || null;
    start({
      userId: values.userId,
      size: values.size,
      scene: values.scene,
      query,
    });
  };
  const rerun = (entry: HistoryEntry<FeedDeliveryInput, FeedDeliveryView>) => {
    if (busy || pending) return;
    form.setFieldsValue(entry.params);
    setDrawerOpen(false);
    start(entry.params);
  };
  const selected = history.entries.filter((e) => history.selected.includes(e.id));
  const accent = ACCENTS.rerank;
  return (
    <Space direction="vertical" size={16} style={{ width: '100%', minWidth: 0 }}>
      <PageHeader
        title="混排 Feed · Sandbox"
        accent={accent}
        description="最终混排图文至少 50% 连续可见 1 秒后提交回执。"
        extra={<Button onClick={() => setDrawerOpen(true)}>请求历史</Button>}
      />
      <Alert
        type="info"
        showIcon
        message="仅测试账户 · 自然内容不计费"
        description="资格有效期五分钟。离屏或切到后台会重新计时；关闭页面前未提交的观测可能无法恢复。"
      />
      {!user?.roles.includes('ADMIN') && (
        <Alert type="warning" showIcon message="当前身份需要 ADMIN 权限" />
      )}
      {storageError && (
        <Alert
          type="warning"
          showIcon
          message="原操作无法保存，请保留操作 ID；刷新后可能无法恢复。"
        />
      )}
      <Card size="small">
        <Form
          form={form}
          layout="vertical"
          onFinish={submit}
          initialValues={{
            userId: String(global.userId),
            query: '',
            size: 12,
            scene: 'feed',
          }}
        >
          <Space align="start" wrap>
            <Form.Item
              name="userId"
              label="模拟用户 ID"
              rules={[
                {
                  validator: (_, value) =>
                    positiveLong(value)
                      ? Promise.resolve()
                      : Promise.reject(new Error('请输入 1～9223372036854775807 的整数 ID')),
                },
              ]}
            >
              <Input style={{ width: 210 }} maxLength={19} />
            </Form.Item>
            <Form.Item
              name="query"
              label="搜索词（可选）"
              rules={[
                {
                  required: false,
                  whitespace: true,
                  max: 500,
                  message: '请输入 1～500 字搜索词',
                },
              ]}
            >
              <Input style={{ width: 230 }} maxLength={500} />
            </Form.Item>
            <Form.Item name="size" label="结果数" rules={[{ required: true }]}>
              <InputNumber min={1} max={50} precision={0} />
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
      {delivery?.adDisposition === AdDisposition.CANDIDATES_UNAVAILABLE && (
        <Alert
          type="warning"
          showIcon
          message="本次广告暂不可用，已交付自然结果"
          description="恢复原操作会保留这次结果。"
        />
      )}
      {delivery?.state === DeliveryState.PREPARING && delivery.items.length > 0 && (
        <Alert
          type="info"
          showIcon
          message="部分广告准备尚未确认"
          description="已交付内容可以浏览；恢复原操作将查询原位置的广告。"
        />
      )}
      {delivery?.items.map((entry) =>
        entry.kind === FeedEntryKind.NATURAL ? (
          <NaturalExposureCard
            key={entry.natural.qualificationId}
            item={entry.natural}
            enabled={!!identity && !drawerOpen && !compareOpen}
          />
        ) : (
          <FeedAdCard
            key={entry.ad.childId}
            item={entry.ad}
            enabled={!!identity && !drawerOpen && !compareOpen}
          />
        ),
      )}
      {delivery && delivery.items.length === 0 && (
        <Card size="small">
          <EmptyState
            icon={
              delivery.state === DeliveryState.PREPARING ? (
                <ClockCircleOutlined />
              ) : (
                <AppstoreOutlined />
              )
            }
            title={
              delivery.state === DeliveryState.PREPARING
                ? '操作准备中，请恢复原操作'
                : '本次没有可交付内容'
            }
            description={
              delivery.state === DeliveryState.COMPLETE ? '可以调整参数后发起新交付。' : undefined
            }
            accent={accent}
          />
        </Card>
      )}
      {delivery && (
        <div style={{ overflowWrap: 'anywhere' }}>
          <TracePanel
            traceId={delivery.traceId ?? undefined}
            requestId={delivery.operationId}
            raw={delivery}
          />
        </div>
      )}
      <HistoryDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        entries={history.entries}
        selected={history.selected}
        onToggleSelect={history.toggleSelect}
        onRerun={rerun}
        rerunDisabled={busy || pending || !identity}
        onCompare={() => setCompareOpen(true)}
        onClear={history.clear}
        renderSummary={(entry) => (
          <Typography.Text style={{ overflowWrap: 'anywhere' }}>
            {entry.params.query || '无搜索词'} · 原用户 {entry.params.userId} ·{' '}
            {entry.data.items.length} 条
          </Typography.Text>
        )}
      />
      <Modal
        title="混排交付对比（只读）"
        open={compareOpen}
        onCancel={() => setCompareOpen(false)}
        footer={null}
        width={760}
      >
        {selected.length === 2 && (
          <ResultDiff
            aLabel={selected[0].params.query || '混排 A'}
            bLabel={selected[1].params.query || '混排 B'}
            aItems={selected[0].data.items}
            bItems={selected[1].data.items}
            keyOf={(item) =>
              item.kind === FeedEntryKind.NATURAL ? item.natural.itemId : item.ad.itemId
            }
            primary={(item) =>
              item.kind === FeedEntryKind.NATURAL ? item.natural.title : item.ad.title
            }
            columns={[{ label: '最终位置', get: (item) => item.slot }]}
          />
        )}
      </Modal>
    </Space>
  );
}
export default function SandboxFeedConsole() {
  return (
    <ExposureScope queue={feedScopeQueue}>
      <FeedPage />
    </ExposureScope>
  );
}
