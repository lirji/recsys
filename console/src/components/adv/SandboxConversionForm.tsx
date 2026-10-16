import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Form, Input, Space, Typography } from 'antd';
import {
  ACTION_POLICY,
  UUID_PATTERN,
  validOccurredAt,
  type ConversionInput,
} from '../../api/sandboxActions';
import { actionQueue, outcomeKey } from '../../exposure/actionReceiptQueue';
import { useConversionActor } from './ConversionActorScope';
import ConversionOutcome from './ConversionOutcome';

interface Values {
  eventId: string;
  qualificationToken: string;
  occurredAt: string;
}
export interface ConversionTarget {
  advertiserId: string;
  qualificationId?: string;
  qualificationToken?: string;
  expiresAt?: string;
}
/** 提交动作冻结事件与原时间；新事件只能由单独按钮触发，不作为失败自动恢复。 */
export default function SandboxConversionForm({ target }: { target: ConversionTarget }) {
  const feedback = useRef<HTMLDivElement>(null);
  const identity = useConversionActor(),
    queue = actionQueue();
  useSyncExternalStore(queue.subscribe, queue.snapshot);
  const [form] = Form.useForm<Values>(),
    [submitted, setSubmitted] = useState<string | null>(null),
    [error, setError] = useState('');
  useEffect(() => {
    if (submitted) feedback.current?.focus();
  }, [submitted]);
  const reset = () => {
    setSubmitted(null);
    setError('');
    form.setFieldsValue({
      eventId: crypto.randomUUID(),
      qualificationToken: target.qualificationToken ?? '',
      occurredAt: new Date().toISOString(),
    });
  };
  useEffect(() => {
    reset();
    return () => form.resetFields();
  }, [identity?.scope, identity?.epoch, target.advertiserId, target.qualificationId]); // eslint-disable-line react-hooks/exhaustive-deps
  const submit = (values: Values) => {
    if (!identity || submitted) return;
    const input: ConversionInput = {
      kind: 'CONVERSION',
      advertiserId: target.advertiserId,
      qualificationToken: values.qualificationToken,
      occurredAt: values.occurredAt,
    };
    const entry = queue.submitConversion(
      values.eventId,
      input,
      target.qualificationId,
      target.expiresAt,
    );
    if (!entry) {
      setError(
        '无法建立事件：请检查身份、资格期限或队列容量；已有事件请使用原标识查询，不要更换其内容。',
      );
      return;
    }
    setSubmitted(values.eventId);
    setError('');
    form.setFieldValue('qualificationToken', '');
  };
  const entry = submitted ? queue.get(outcomeKey(target.advertiserId, submitted)) : undefined;
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="Sandbox 业务转化"
        description="每次提交和查询都须广告主 edit 权限；管理员角色也不绕过判权。发生时间为 UTC，提交后保持原值。关闭表单不撤销已提交事件。"
      />
      <Typography.Text style={{ overflowWrap: 'anywhere' }}>
        广告主 {target.advertiserId}
        {target.qualificationId && ` · 资格 ${target.qualificationId}`}
      </Typography.Text>
      {error && <Alert type="warning" showIcon message={error} />}
      {!identity && <Alert type="warning" message="等待有效 ADMIN / ADVERTISER 身份" />}
      <Form form={form} layout="vertical" onFinish={submit} disabled={!identity || !!submitted}>
        <Form.Item
          name="eventId"
          label="业务事件 ID"
          rules={[{ required: true, pattern: UUID_PATTERN, message: '请输入 UUID 业务事件 ID' }]}
        >
          <Input maxLength={36} />
        </Form.Item>
        <Form.Item
          name="qualificationToken"
          label="资格证明"
          rules={[{ required: true, whitespace: true, max: ACTION_POLICY.tokenMax }]}
        >
          <Input.Password
            autoComplete="off"
            maxLength={ACTION_POLICY.tokenMax}
            placeholder={submitted ? '已冻结并移入短期恢复队列' : '粘贴当前广告主的有效资格证明'}
          />
        </Form.Item>
        <Form.Item
          name="occurredAt"
          label="业务发生时间（UTC）"
          rules={[
            {
              validator: (_, v) =>
                validOccurredAt(v)
                  ? Promise.resolve()
                  : Promise.reject(new Error('使用 UTC Z 时间，最多六位小数')),
            },
          ]}
        >
          <Input maxLength={32} placeholder="2026-10-16T02:00:00.000Z" />
        </Form.Item>
        <Button type="primary" htmlType="submit">
          提交业务事件
        </Button>
      </Form>
      <div ref={feedback} tabIndex={-1} aria-live="polite">
        {submitted && <Typography.Text>已冻结原事件，后续使用原标识查询。</Typography.Text>}
        <Typography.Text type="secondary">
          证明仅用于本次事件。未确认负载最多保留 5 分钟，不会延长服务端资格窗口；原标识最多保留 1
          小时。
        </Typography.Text>
      </div>
      {entry && target.qualificationId && <ConversionOutcome entry={entry} />}
      {!submitted &&
        target.qualificationId &&
        queue
          .outcomes(target.advertiserId)
          .filter((e) => e.expectedQualificationId === target.qualificationId)
          .map((e) => <ConversionOutcome key={e.key} entry={e} />)}
      {submitted && (
        <Button
          disabled={!identity || !!entry?.input || queue.busy(entry?.key ?? '')}
          onClick={reset}
        >
          开始另一业务事件
        </Button>
      )}
    </Space>
  );
}
