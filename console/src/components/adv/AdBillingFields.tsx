import { Form, InputNumber, Select } from 'antd';

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
        extra="Look-alike audience_id,空=不定向"
        style={{ flex: 1 }}
      >
        <InputNumber min={0} style={{ width: '100%' }} placeholder="可选" />
      </Form.Item>
    </>
  );
}
