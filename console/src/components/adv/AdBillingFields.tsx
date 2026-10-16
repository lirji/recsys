import { Form, Input, InputNumber, Select } from 'antd';

const BILLING_OPTIONS = [
  { value: 'CPC', label: 'CPC 按点击' },
  { value: 'OCPC', label: 'oCPC 目标转化出价' },
  { value: 'CPM', label: 'CPM 按千次曝光' },
  { value: 'OCPM', label: 'oCPM' },
  { value: 'CPA', label: 'CPA 按转化' },
];

export default function AdBillingFields() {
  return (
    <>
      <Form.Item name="optimizationType" label="计费类型" style={{ flex: 1 }}>
        <Select options={BILLING_OPTIONS} />
      </Form.Item>
      <Form.Item
        name="targetCpa"
        label="目标 CPA"
        extra="oCPC / oCPM / CPA 必填"
        style={{ flex: 1 }}
      >
        <InputNumber min={0} style={{ width: '100%' }} />
      </Form.Item>
      <Form.Item
        name="audienceId"
        label="定向人群"
        rules={[
          { pattern: /^[1-9]\d{0,18}$/, message: '请输入完整的正整数人群 ID，留空可取消定向' },
        ]}
        extra="Look-alike audience_id,空=不定向"
        style={{ flex: 1 }}
      >
        <Input inputMode="numeric" maxLength={19} placeholder="可选" />
      </Form.Item>
    </>
  );
}
