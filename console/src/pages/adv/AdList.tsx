import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, Tag } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { createAd, listAds } from '../../api/advertiser';
import { toApiError } from '../../api/client';
import type { AdUpsert, AdView } from '../../api/types';
import { StatusTag } from '../../components/adv/statusTags';

export default function AdList() {
  const { advId } = useParams();
  const advertiserId = Number(advId);
  const { message } = App.useApp();
  const query = useQuery({ queryKey: ['ads', advertiserId], queryFn: () => listAds(advertiserId) });
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<AdUpsert>();

  const submit = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await createAd(advertiserId, values);
      message.success('已创建广告(进入审核流程)');
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
    { title: 'adId', dataIndex: 'adId', width: 80, render: (id: number) => <Link to={`/advertiser/ad/${id}`}>{id}</Link> },
    { title: '标题', dataIndex: 'title', render: (t: string, r: AdView) => <Link to={`/advertiser/ad/${r.adId}`}>{t || `#${r.itemId}`}</Link> },
    { title: '状态', dataIndex: 'status', width: 100, render: (s: string) => <StatusTag status={s} /> },
    { title: '计费', dataIndex: 'optimizationType', width: 90, render: (o: string) => <Tag>{o}</Tag> },
    { title: '质量度', dataIndex: 'qualityScore', width: 90, render: (v: number) => v?.toFixed(3) },
    { title: '向量', dataIndex: 'hasEmbedding', width: 70, render: (b: boolean) => (b ? <Tag color="green">有</Tag> : <Tag>无</Tag>) },
    { title: '词/创意', key: 'cnt', width: 90, render: (_: unknown, r: AdView) => `${r.bidwords?.length ?? 0} / ${r.creatives?.length ?? 0}` },
  ];

  return (
    <Card
      title={`广告主 #${advertiserId} 的广告`}
      extra={
        <Button type="primary" onClick={() => setOpen(true)}>
          新建广告
        </Button>
      }
    >
      {query.isError ? (
        <Alert type="error" showIcon message={toApiError(query.error).message} />
      ) : (
        <Table<AdView> rowKey="adId" size="small" loading={query.isLoading} columns={columns} dataSource={query.data ?? []} pagination={false} />
      )}

      <Modal title="新建广告" open={open} onOk={submit} confirmLoading={saving} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" initialValues={{ optimizationType: 'CPC', status: 'active', qualityScore: 1.0 }}>
          <Form.Item name="itemId" label="关联 itemId(创意/向量来源)" rules={[{ required: true, message: '必填' }]}>
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '必填' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="landingUrl" label="落地页 URL">
            <Input placeholder="https://..." />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="optimizationType" label="计费类型" style={{ flex: 1 }}>
              <Select options={[{ value: 'CPC', label: 'CPC' }, { value: 'OCPC', label: 'OCPC' }]} />
            </Form.Item>
            <Form.Item name="targetCpa" label="目标 CPA(OCPC)" style={{ flex: 1 }}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="qualityScore" label="质量度" style={{ flex: 1 }}>
              <InputNumber min={0} step={0.1} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </Card>
  );
}
