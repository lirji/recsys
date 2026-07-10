import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Form, Input, InputNumber, Modal, Progress, Select, Space, Table } from 'antd';
import { Link } from 'react-router-dom';
import { createAdvertiser, listAdvertisers } from '../../api/advertiser';
import { toApiError } from '../../api/client';
import type { AdvertiserUpsert, AdvertiserView } from '../../api/types';
import { StatusTag } from '../../components/adv/statusTags';

export default function AdvertiserList() {
  const { message } = App.useApp();
  const query = useQuery({ queryKey: ['advertisers'], queryFn: listAdvertisers });
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<AdvertiserUpsert>();
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await createAdvertiser(values);
      message.success('已创建广告主');
      setOpen(false);
      form.resetFields();
      query.refetch();
    } catch (e) {
      message.error(toApiError(e).message);
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    {
      title: 'ID',
      dataIndex: 'advertiserId',
      width: 90,
      render: (id: number) => <Link to={`/advertiser/${id}`}>{id}</Link>,
    },
    { title: '名称', dataIndex: 'name', render: (n: string, r: AdvertiserView) => <Link to={`/advertiser/${r.advertiserId}`}>{n}</Link> },
    { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <StatusTag status={s} /> },
    { title: '日预算', dataIndex: 'dailyBudget', width: 110, render: (v: number) => v?.toFixed(2) },
    {
      title: '今日已花 / 剩余',
      key: 'budget',
      width: 220,
      render: (_: unknown, r: AdvertiserView) => {
        const pct = r.dailyBudget > 0 ? Math.min(100, (r.spentToday / r.dailyBudget) * 100) : 0;
        return (
          <Space direction="vertical" size={0} style={{ width: 200 }}>
            <Progress percent={Math.round(pct)} size="small" />
            <span className="mono" style={{ fontSize: 12, color: '#888' }}>
              {r.spentToday?.toFixed(2)} / 剩 {r.remainingBudget?.toFixed(2)}
            </span>
          </Space>
        );
      },
    },
    {
      title: '操作',
      key: 'act',
      width: 160,
      render: (_: unknown, r: AdvertiserView) => (
        <Space>
          <Link to={`/advertiser/${r.advertiserId}/ads`}>广告</Link>
          <Link to={`/advertiser/${r.advertiserId}/report`}>报表</Link>
        </Space>
      ),
    },
  ];

  return (
    <Card
      title="广告主"
      extra={
        <Button type="primary" onClick={() => setOpen(true)}>
          新建广告主
        </Button>
      }
    >
      {query.isError ? (
        <Alert type="error" showIcon message={toApiError(query.error).message} />
      ) : (
        <Table
          rowKey="advertiserId"
          size="small"
          loading={query.isLoading}
          columns={columns}
          dataSource={query.data ?? []}
          pagination={false}
        />
      )}

      <Modal title="新建广告主" open={open} onOk={submit} confirmLoading={saving} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" initialValues={{ status: 'active', dailyBudget: 1000 }}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="广告主名称" />
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
