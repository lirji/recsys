import { showApiError, toApiError } from '../../api/errors';
import { queryKeys } from '../../api/queryKeys';
import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Alert, Button, Form, Input, Modal, Popconfirm, Select, Space, Table } from 'antd';
import {
  createCreative,
  deleteCreative,
  listCreatives,
  updateCreative,
} from '../../api/advertiser';
import type { CreativeUpsert, CreativeView } from '../../api/types';
import { StatusTag } from './statusTags';

export default function CreativePanel({
  adId,
  advertiserId,
  canEdit,
}: {
  adId: string;
  advertiserId: string;
  canEdit: boolean;
}) {
  const { message } = App.useApp();
  const client = useQueryClient();
  const inFlight = useRef(false);
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
    if (!canEdit || inFlight.current) return;
    inFlight.current = true;
    let values;
    try {
      values = await form.validateFields();
    } catch {
      inFlight.current = false;
      return;
    }
    setSaving(true);
    try {
      if (editing) await updateCreative(editing.creativeId, values);
      else await createCreative(adId, values);
      message.success('已保存创意');
      setOpen(false);
      await refresh();
    } catch (e) {
      showApiError(e, message.error);
    } finally {
      inFlight.current = false;
      setSaving(false);
    }
  };

  const remove = async (id: string) => {
    if (!canEdit || inFlight.current) return;
    inFlight.current = true;
    setSaving(true);
    try {
      await deleteCreative(id);
      message.success('已删除');
      await refresh();
    } catch (e) {
      showApiError(e, message.error);
    } finally {
      inFlight.current = false;
      setSaving(false);
    }
  };

  const refresh = async () => {
    await query.refetch();
    await client.invalidateQueries({ queryKey: queryKeys.ad(adId) });
    await client.invalidateQueries({ queryKey: queryKeys.ads(advertiserId) });
  };

  return (
    <>
      {query.isError ? (
        <Alert
          type="error"
          showIcon
          message={toApiError(query.error).message}
          action={
            <Button loading={query.isFetching} onClick={() => query.refetch()}>
              重试
            </Button>
          }
        />
      ) : (
        <Table<CreativeView>
          rowKey="creativeId"
          size="small"
          scroll={{ x: 640 }}
          loading={query.isLoading}
          dataSource={query.data ?? []}
          pagination={false}
          title={() => (
            <Space style={{ justifyContent: 'space-between', display: 'flex' }}>
              <b>创意 (DCO 多套标题)</b>
              <Button
                size="small"
                type="primary"
                disabled={!canEdit || saving}
                onClick={openCreate}
              >
                新增创意
              </Button>
            </Space>
          )}
          columns={[
            { title: 'id', dataIndex: 'creativeId', width: 70 },
            { title: '标题', dataIndex: 'title' },
            { title: '落地页', dataIndex: 'landingUrl', ellipsis: true },
            {
              title: '状态',
              dataIndex: 'status',
              width: 130,
              render: (s: string) => <StatusTag status={s} />,
            },
            {
              title: '操作',
              key: 'act',
              width: 120,
              render: (_: unknown, r: CreativeView) => (
                <Space>
                  <Button
                    type="link"
                    size="small"
                    disabled={!canEdit || saving}
                    onClick={() => openEdit(r)}
                  >
                    编辑
                  </Button>
                  <Popconfirm
                    disabled={!canEdit || saving}
                    title="删除该创意?"
                    onConfirm={() => remove(r.creativeId)}
                  >
                    <Button type="link" size="small" disabled={!canEdit || saving}>
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      )}
      <Modal
        title={editing ? '编辑创意' : '新增创意'}
        open={open}
        onOk={submit}
        confirmLoading={saving}
        okButtonProps={{ disabled: !canEdit }}
        cancelButtonProps={{ disabled: saving }}
        closable={!saving}
        maskClosable={!saving}
        onCancel={() => !saving && setOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical" disabled={!canEdit || saving}>
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
