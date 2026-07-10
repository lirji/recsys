import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Button, Form, Input, Modal, Popconfirm, Select, Space, Table } from 'antd';
import { createCreative, deleteCreative, listCreatives, updateCreative } from '../../api/advertiser';
import { toApiError } from '../../api/client';
import type { CreativeUpsert, CreativeView } from '../../api/types';
import { StatusTag } from './statusTags';

export default function CreativePanel({ adId }: { adId: number }) {
  const { message } = App.useApp();
  const query = useQuery({ queryKey: ['creatives', adId], queryFn: () => listCreatives(adId) });
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<CreativeView | null>(null);
  const [form] = Form.useForm<CreativeUpsert>();
  const [saving, setSaving] = useState(false);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ status: 'approved' });
    setOpen(true);
  };
  const openEdit = (c: CreativeView) => {
    setEditing(c);
    form.setFieldsValue({ title: c.title, landingUrl: c.landingUrl, status: c.status });
    setOpen(true);
  };

  const submit = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editing) await updateCreative(editing.creativeId, values);
      else await createCreative(adId, values);
      message.success('已保存创意');
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
      await deleteCreative(id);
      message.success('已删除');
      query.refetch();
    } catch (e) {
      message.error(toApiError(e).message);
    }
  };

  return (
    <>
      <Table<CreativeView>
        rowKey="creativeId"
        size="small"
        loading={query.isLoading}
        dataSource={query.data ?? []}
        pagination={false}
        title={() => (
          <Space style={{ justifyContent: 'space-between', display: 'flex' }}>
            <b>创意 (DCO 多套标题)</b>
            <Button size="small" type="primary" onClick={openCreate}>
              新增创意
            </Button>
          </Space>
        )}
        columns={[
          { title: 'id', dataIndex: 'creativeId', width: 70 },
          { title: '标题', dataIndex: 'title' },
          { title: '落地页', dataIndex: 'landingUrl', ellipsis: true },
          { title: '状态', dataIndex: 'status', width: 130, render: (s: string) => <StatusTag status={s} /> },
          {
            title: '操作',
            key: 'act',
            width: 120,
            render: (_: unknown, r: CreativeView) => (
              <Space>
                <a onClick={() => openEdit(r)}>编辑</a>
                <Popconfirm title="删除该创意?" onConfirm={() => remove(r.creativeId)}>
                  <a>删除</a>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />
      <Modal title={editing ? '编辑创意' : '新增创意'} open={open} onOk={submit} confirmLoading={saving} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical">
          <Form.Item name="title" label="标题" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="landingUrl" label="落地页 URL">
            <Input placeholder="https://..." />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              options={[
                { value: 'approved', label: 'approved' },
                { value: 'pending_review', label: 'pending_review' },
                { value: 'rejected', label: 'rejected' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
