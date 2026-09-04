import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Alert, Button, Card, Form, Input, InputNumber, Modal, Progress, Select, Space, Table } from 'antd';
import { Link } from 'react-router-dom';
import { createAdvertiser, listAdvertisers } from '../../api/advertiser';
import { queryKeys } from '../../api/queryKeys';
import { toApiError } from '../../api/client';
import type { AdvertiserUpsert, AdvertiserView } from '../../api/types';
import { StatusTag } from '../../components/adv/statusTags';
import PageHeader from '../../components/PageHeader';
import { ACCENTS } from '../../theme/tokens';

export default function AdvertiserList() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: queryKeys.advertisers(), queryFn: listAdvertisers });
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<AdvertiserUpsert>();
  const createMut = useMutation({
    mutationFn: createAdvertiser,
    onSuccess: async () => {
      message.success('已创建广告主');
      setOpen(false);
      form.resetFields();
      await queryClient.invalidateQueries({ queryKey: queryKeys.advertisers() });
    },
    onError: (e) => message.error(toApiError(e).message),
  });

  const submit = async () => {
    const values = await form.validateFields();
    createMut.mutate(values);
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
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <PageHeader
      title="广告主"
      accent={ACCENTS.ad}
      description="广告主账户、日预算与投放入口。"
      extra={
        <Button type="primary" onClick={() => setOpen(true)}>
          新建广告主
        </Button>
      }
    />
    <Card>
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
          scroll={{ x: 800 }}
        />
      )}

      <Modal title="新建广告主" open={open} onOk={submit} confirmLoading={createMut.isPending} onCancel={() => setOpen(false)} destroyOnClose>
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
    </Space>
  );
}
