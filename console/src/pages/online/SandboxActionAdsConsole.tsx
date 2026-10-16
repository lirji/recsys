import { DeliveryState, PreparationStatus } from '../../api/sandboxActions';
import { HttpStatusCode } from 'axios';
import { useEffect, useRef, useState } from 'react';
import axios from 'axios';
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
import {
  actionDelivery,
  UUID_PATTERN,
  validDeliveryInput,
  validLongId,
  type ActionDelivery,
  type ActionAdItem,
} from '../../api/sandboxActions';
import type { SandboxDeliveryInput } from '../../api/exposure';
import { toApiError, formatError } from '../../api/errors';
import ExposureScope, { useExposureScope } from '../../components/exposure/ExposureScope';
import ActionAdCreative from '../../components/exposure/ActionAdCreative';
import ActionReceiptStatus from '../../components/exposure/ActionReceiptStatus';
import SettlementStatus from '../../components/exposure/SettlementStatus';
import PageHeader from '../../components/PageHeader';
import HistoryDrawer from '../../components/debug/HistoryDrawer';
import TracePanel from '../../components/explain/TracePanel';
import { useRequestHistory } from '../../hooks/useRequestHistory';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import { actionQueue } from '../../exposure/actionReceiptQueue';
import { exposureEpoch, onExposureRevoked } from '../../exposure/identity';
import { ACCENTS } from '../../theme/tokens';

const KEY = 'recsys.sandbox.click.delivery.v1';
interface Intent {
  operationId: string;
  input: SandboxDeliveryInput;
  scope: string;
  createdAt: number;
}
function ActionPage() {
  const identity = useExposureScope(),
    global = useGlobalUser();
  const [form] = Form.useForm<SandboxDeliveryInput>();
  const [intent, setIntent] = useState<Intent | null>(null),
    [delivery, setDelivery] = useState<ActionDelivery | null>(null);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [storageError, setStorageError] = useState(false);
  const [live, setLive] = useState(false),
    [historyOpen, setHistoryOpen] = useState(false),
    [compareOpen, setCompareOpen] = useState(false);
  const history = useRequestHistory<SandboxDeliveryInput, ActionDelivery>();
  const request = useRef<AbortController | null>(null),
    ownerRef = useRef(identity),
    intentRef = useRef(intent);
  ownerRef.current = identity;
  intentRef.current = intent;
  const execute = async (value: Intent, mode: 'new' | 'recover' | 'query') => {
    const owner = ownerRef.current;
    if (!owner || owner.scope !== value.scope || owner.epoch !== exposureEpoch()) return;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    const active = () =>
      !controller.signal.aborted &&
      ownerRef.current?.scope === owner.scope &&
      exposureEpoch() === owner.epoch;
    setBusy(true);
    setError('');
    const start = performance.now();
    try {
      let result: ActionDelivery;
      if (mode === 'new')
        result = await actionDelivery(
          'put',
          value.operationId,
          value.input,
          owner,
          controller.signal,
        );
      else {
        try {
          result = await actionDelivery(
            'get',
            value.operationId,
            value.input,
            owner,
            controller.signal,
          );
        } catch (e) {
          if (
            mode !== 'recover' ||
            !active() ||
            !axios.isAxiosError(e) ||
            e.response?.status !== HttpStatusCode.NotFound
          )
            throw e;
          result = await actionDelivery(
            'put',
            value.operationId,
            value.input,
            owner,
            controller.signal,
          );
        }
        if (mode === 'recover' && result.state === DeliveryState.PREPARING && active())
          result = await actionDelivery(
            'put',
            value.operationId,
            value.input,
            owner,
            controller.signal,
          );
      }
      if (!active()) return;
      setDelivery(result);
      if (mode !== 'query') setLive(true);
      if (result.state === DeliveryState.COMPLETE)
        history.push(value.input, result, Math.round(performance.now() - start));
    } catch (e) {
      if (active()) setError(toApiError(e).message);
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
      request.current = null;
      intentRef.current = null;
      setIntent(null);
      setDelivery(null);
      setLive(false);
      setBusy(false);
      setError('');
      setHistoryOpen(false);
      setCompareOpen(false);
      history.clear();
    };
    clear();
    if (identity) {
      try {
        const raw = sessionStorage.getItem(KEY);
        if (raw && raw.length <= 8192) {
          const v = JSON.parse(raw) as Intent;
          if (
            v.scope === identity.scope &&
            UUID_PATTERN.test(v.operationId) &&
            validDeliveryInput(v.input) &&
            Number.isFinite(v.createdAt) &&
            v.createdAt <= Date.now() &&
            Date.now() - v.createdAt < 3600000
          ) {
            const restored = {
              operationId: v.operationId,
              input: {
                userId: v.input.userId,
                query: v.input.query,
                slots: v.input.slots,
                scene: v.input.scene,
              },
              scope: v.scope,
              createdAt: v.createdAt,
            };
            intentRef.current = restored;
            setIntent(restored);
            form.setFieldsValue(restored.input);
            void execute(restored, 'query');
          } else sessionStorage.removeItem(KEY);
        }
      } catch {
        setStorageError(true);
      }
    }
    const off = onExposureRevoked(clear);
    return () => {
      off();
      request.current?.abort();
    };
    // 恢复只由身份边界触发；普通渲染不能创建新操作或重新采集。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [identity?.scope, identity?.epoch]);
  const start = (input: SandboxDeliveryInput) => {
    if (
      !identity ||
      request.current ||
      (intentRef.current && delivery?.state !== DeliveryState.COMPLETE) ||
      !validDeliveryInput(input)
    )
      return;
    const value = {
      operationId: crypto.randomUUID(),
      input: Object.freeze({ ...input }),
      scope: identity.scope,
      createdAt: Date.now(),
    };
    intentRef.current = value;
    setIntent(value);
    setDelivery(null);
    setLive(false);
    try {
      sessionStorage.setItem(KEY, JSON.stringify(value));
    } catch {
      setStorageError(true);
    }
    void execute(value, 'new');
  };
  const enabled =
    !!identity &&
    live &&
    !historyOpen &&
    !compareOpen &&
    delivery?.state === DeliveryState.COMPLETE;
  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="CPC / OCPC · Sandbox"
        accent={ACCENTS.ad}
        description="有效可见与点击均被接受后，才可能结算；报价不是实收。"
        extra={<Button onClick={() => setHistoryOpen(true)}>请求历史</Button>}
      />
      <Alert
        type="info"
        showIcon
        message="仅测试账户 · 五分钟资格"
        description="创意至少 50% 连续可见 1 秒；点击可先发生。结果由服务端确认，关闭页面不撤销已提交事件。"
      />
      {!identity && (
        <Alert type="warning" showIcon message="等待管理员身份；当前无法提交新交付或回执" />
      )}
      {storageError && (
        <Alert type="warning" showIcon message={formatError('DELIVERY_STORAGE_UNAVAILABLE')} />
      )}
      <Card size="small">
        <Form
          form={form}
          layout="vertical"
          onFinish={start}
          initialValues={{
            userId: Number.isSafeInteger(global.userId) ? String(global.userId) : '',
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
                  validator: (_, v) =>
                    validLongId(v)
                      ? Promise.resolve()
                      : Promise.reject(new Error('请输入有效的正整数 ID')),
                },
              ]}
            >
              <Input maxLength={19} style={{ width: 200 }} />
            </Form.Item>
            <Form.Item
              name="query"
              label="搜索词"
              rules={[{ required: true, whitespace: true, max: 500 }]}
            >
              <Input maxLength={500} style={{ width: 220 }} />
            </Form.Item>
            <Form.Item name="slots" label="广告位" rules={[{ required: true }]}>
              <InputNumber min={1} max={10} precision={0} />
            </Form.Item>
            <Form.Item
              name="scene"
              label="场景"
              rules={[{ required: true, whitespace: true, max: 100 }]}
            >
              <Input maxLength={100} style={{ width: 150 }} />
            </Form.Item>
          </Space>
          <Space wrap>
            <Button
              type="primary"
              htmlType="submit"
              loading={busy}
              disabled={!identity || (!!intent && delivery?.state !== DeliveryState.COMPLETE)}
            >
              新交付
            </Button>
            {intent && (
              <>
                <Button disabled={!identity || busy} onClick={() => execute(intent, 'recover')}>
                  恢复原操作
                </Button>
                <Button disabled={!identity || busy} onClick={() => execute(intent, 'query')}>
                  查询原操作
                </Button>
              </>
            )}
            {!!delivery && !live && (
              <Button
                disabled={!identity || delivery.state !== DeliveryState.COMPLETE}
                onClick={() => setLive(true)}
              >
                继续观测原资格
              </Button>
            )}
          </Space>
        </Form>
      </Card>
      {intent && (
        <Typography.Text style={{ overflowWrap: 'anywhere' }}>
          操作 {intent.operationId} · 原用户 {intent.input.userId} ·{' '}
          {delivery?.state === DeliveryState.COMPLETE ? '准备完成' : '准备结果待确认'}
        </Typography.Text>
      )}
      {error && (
        <Alert
          type="warning"
          showIcon
          message="交付结果待确认"
          description={
            <>
              {error}
              <br />
              尚未查到原结果或此环境未安装能力时，保留原操作查询；不会转用旧收费接口。
            </>
          }
        />
      )}
      <Table<ActionAdItem>
        rowKey="childId"
        pagination={false}
        loading={busy}
        dataSource={delivery?.items ?? []}
        scroll={{ x: 1160 }}
        locale={{
          emptyText:
            delivery?.state === DeliveryState.COMPLETE
              ? '本次没有可交付的 CPC / OCPC 广告'
              : '主动新交付后查看原位置结果',
        }}
        columns={[
          {
            title: '位置 / 创意',
            width: 300,
            render: (_, item) => <ActionAdCreative item={item} enabled={enabled} />,
          },
          {
            title: '准备 / 单次报价',
            width: 160,
            render: (_, item) => (
              <Space direction="vertical">
                <Tag>{item.bidType}</Tag>
                <Typography.Text>{item.preparation?.status ?? '准备中'}</Typography.Text>
                <Typography.Text>
                  {item.preparation?.status === PreparationStatus.QUALIFIED
                    ? `¥${item.preparation.amount} / 次点击`
                    : '无可计费资格'}
                </Typography.Text>
              </Space>
            ),
          },
          {
            title: '可见与点击条件',
            width: 340,
            render: (_, item) =>
              item.preparation?.status === PreparationStatus.QUALIFIED ? (
                <Space align="start">
                  <ActionReceiptStatus childId={item.childId} kind="VISIBLE" />
                  <ActionReceiptStatus childId={item.childId} kind="CLICK" />
                </Space>
              ) : (
                '—'
              ),
          },
          {
            title: '当前资格结算',
            width: 280,
            render: (_, item) =>
              item.preparation?.status === PreparationStatus.QUALIFIED && identity ? (
                <SettlementStatus childId={item.childId} />
              ) : (
                '—'
              ),
          },
        ]}
      />
      {delivery && <TracePanel raw={delivery} />}
      <HistoryDrawer
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        entries={history.entries}
        selected={history.selected}
        onToggleSelect={history.toggleSelect}
        onRerun={(e) => {
          form.setFieldsValue(e.params);
          setHistoryOpen(false);
        }}
        rerunDisabled={busy}
        onCompare={() => {
          setHistoryOpen(false);
          setCompareOpen(true);
        }}
        onClear={history.clear}
        renderSummary={(e) => (
          <span>
            用户 {e.params.userId} · {e.params.query}（重跑仅填入条件，须主动新交付）
          </span>
        )}
      />
      <Modal
        title="只读交付对比"
        open={compareOpen}
        onCancel={() => setCompareOpen(false)}
        footer={null}
        width={900}
      >
        {history.entries
          .filter((e) => history.selected.includes(e.id))
          .map((e) => (
            <TracePanel key={e.id} raw={e.data} />
          ))}
      </Modal>
    </Space>
  );
}
export default function SandboxActionAdsConsole() {
  return (
    <ExposureScope queue={actionQueue()}>
      <ActionPage />
    </ExposureScope>
  );
}
