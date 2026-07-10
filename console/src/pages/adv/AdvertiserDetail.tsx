import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Descriptions, Form, Input, InputNumber, Modal, Select, Space, Spin } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { getAdvertiser, updateAdvertiser } from '../../api/advertiser';
import { toApiError } from '../../api/client';
import type { AdvertiserUpsert } from '../../api/types';
import { StatusTag } from '../../components/adv/statusTags';

export default function AdvertiserDetail() {
  const { id } = useParams();
  const advertiserId = Number(id);
  const { message } = App.useApp();
  const query = useQuery({ queryKey: ['advertiser', advertiserId], queryFn: () => getAdvertiser(advertiserId) });
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<AdvertiserUpsert>();

  useEffect(() => {
    if (query.data) form.setFieldsValue({ name: query.data.name, dailyBudget: query.data.dailyBudget, status: query.data.status });
  }, [query.data, form]);

  const submit = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await updateAdvertiser(advertiserId, values);
      message.success('已更新');
      setOpen(false);
      query.refetch();
    } catch (e) {
      message.error(toApiError(e).message);
    } finally {
      setSaving(false);
    }
  };

  if (query.isLoading) return <Spin />;
  if (query.isError) return <Alert type="error" showIcon message={toApiError(query.error).message} />;
  const a = query.data!;

  return (
    <Card
      title={`广告主 #${a.advertiserId}`}
      extra={
        <Space>
          <Link to={`/advertiser/${advertiserId}/ads`}>
            <Button>广告列表</Button>
          </Link>
          <Link to={`/advertiser/${advertiserId}/report`}>
            <Button>投放报表</Button>
          </Link>
          <Button type="primary" onClick={() => setOpen(true)}>
            编辑
          </Button>
        </Space>
      }
    >
      <Descriptions bordered column={2} size="small">
        <Descriptions.Item label="名称">{a.name}</Descriptions.Item>
        <Descriptions.Item label="状态">
          <StatusTag status={a.status} />
        </Descriptions.Item>
        <Descriptions.Item label="日预算">{a.dailyBudget?.toFixed(2)}</Descriptions.Item>
        <Descriptions.Item label="今日已花">{a.spentToday?.toFixed(2)}</Descriptions.Item>
        <Descriptions.Item label="剩余预算">{a.remainingBudget?.toFixed(2)}</Descriptions.Item>
      </Descriptions>

      <Modal title="编辑广告主" open={open} onOk={submit} confirmLoading={saving} onCancel={() => setOpen(false)}>
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="dailyBudget" label="日预算">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select options={[{ value: 'active', label: 'active' }, { value: 'paused', label: 'paused' }]} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
