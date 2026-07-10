import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag } from 'antd';
import { createBidword, deleteBidword, listBidwords, updateBidword } from '../../api/advertiser';
import { toApiError } from '../../api/client';
import type { BidwordUpsert, BidwordView } from '../../api/types';

export default function BidwordPanel({ adId }: { adId: number }) {
  const { message } = App.useApp();
  const query = useQuery({ queryKey: ['bidwords', adId], queryFn: () => listBidwords(adId) });
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<BidwordView | null>(null);
  const [form] = Form.useForm<BidwordUpsert>();
  const [saving, setSaving] = useState(false);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ matchType: 'EXACT', bidMode: 'CPC', bid: 1.0 });
    setOpen(true);
  };
  const openEdit = (b: BidwordView) => {
    setEditing(b);
    form.setFieldsValue({ keyword: b.keyword, matchType: b.matchType, bid: b.bid, bidMode: b.bidMode });
    setOpen(true);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editing) await updateBidword(editing.id, values);
      else await createBidword(adId, values);
      message.success('已保存竞价词');
      setOpen(false);
      query.refetch();
    } catch (e) {
      message.error(toApiError(e).message);
    } finally {
      setSaving(false);
    }
  };

  const remove = async (id: number) => {
    try {
      await deleteBidword(id);
      message.success('已删除');
      query.refetch();
    } catch (e) {
      message.error(toApiError(e).message);
    }
  };

  return (
    <>
      <Table<BidwordView>
        rowKey="id"
        size="small"
        loading={query.isLoading}
        dataSource={query.data ?? []}
        pagination={false}
        title={() => (
          <Space style={{ justifyContent: 'space-between', display: 'flex' }}>
            <b>竞价词(在线关键词倒排来源)</b>
            <Button size="small" type="primary" onClick={openCreate}>
              新增竞价词
            </Button>
          </Space>
        )}
        columns={[
          { title: 'id', dataIndex: 'id', width: 70 },
          { title: '关键词', dataIndex: 'keyword' },
          { title: '匹配', dataIndex: 'matchType', width: 100, render: (m: string) => <Tag>{m}</Tag> },
          { title: '出价', dataIndex: 'bid', width: 90, render: (v: number) => v?.toFixed(3) },
          { title: '模式', dataIndex: 'bidMode', width: 90, render: (m: string) => <Tag>{m}</Tag> },
          {
            title: '操作',
            key: 'act',
            width: 120,
            render: (_: unknown, r: BidwordView) => (
              <Space>
                <a onClick={() => openEdit(r)}>编辑</a>
                <Popconfirm title="删除该竞价词?" onConfirm={() => remove(r.id)}>
                  <a>删除</a>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
      <Modal title={editing ? '编辑竞价词' : '新增竞价词'} open={open} onOk={submit} confirmLoading={saving} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical">
          <Form.Item name="keyword" label="关键词" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="matchType" label="匹配类型" style={{ flex: 1 }}>
              <Select
                options={[
                  { value: 'EXACT', label: 'EXACT' },
                  { value: 'PHRASE', label: 'PHRASE' },
                  { value: 'BROAD', label: 'BROAD' },
                ]}
              />
            </Form.Item>
            <Form.Item name="bid" label="出价" style={{ flex: 1 }}>
              <InputNumber min={0} step={0.1} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="bidMode" label="出价模式" style={{ flex: 1 }}>
              <Select
                options={[
                  { value: 'CPC', label: 'CPC' },
                  { value: 'oCPC', label: 'oCPC' },
                  { value: 'oCPM', label: 'oCPM' },
                ]}
              />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </>
  );
}
