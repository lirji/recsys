import { showApiError } from '../../api/errors';
import { useAdvertiserPermissions } from '../../hooks/useAdvertiserPermissions';
import PermissionNotice from '../../components/adv/PermissionNotice';
import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Alert, Button, Card, Form, Input, InputNumber, Modal, Space, Table, Tag } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { createAd, listAds } from '../../api/advertiser';
import { queryKeys } from '../../api/queryKeys';
import { toApiError } from '../../api/client';
import type { AdUpsert, AdView } from '../../api/types';
import { StatusTag, ReviewStatusTag } from '../../components/adv/statusTags';
import AdBillingFields from '../../components/adv/AdBillingFields';
import PageHeader from '../../components/PageHeader';
import { ACCENTS } from '../../theme/tokens';
import { formatId } from '../../utils/formatId';

export default function AdList() {
  const { advId } = useParams();
  const advertiserId = advId ?? '';
  const { message } = App.useApp();
  const access = useAdvertiserPermissions(advertiserId);
  const queryClient = useQueryClient();
  const inFlight = useRef(false);
  const query = useQuery({
    queryKey: queryKeys.ads(advertiserId),
    queryFn: () => listAds(advertiserId),
  });
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm<AdUpsert>();
  const createMut = useMutation({
    mutationFn: (values: AdUpsert) => createAd(advertiserId, values),
    onSuccess: async () => {
      message.success('已创建广告(进入审核流程)');
      setOpen(false);
      form.resetFields();
      await queryClient.invalidateQueries({ queryKey: queryKeys.ads(advertiserId) });
    },
    onError: (e) => {
      showApiError(e, message.error);
      void access.query.refetch();
    },
  });

  const submit = async () => {
    if (!access.can('editAds') || inFlight.current) return;
    inFlight.current = true;
    try {
      await createMut.mutateAsync(await form.validateFields());
    } catch {
      /* 字段错误留在表单，接口错误由 mutation.onError 展示。 */
    } finally {
      inFlight.current = false;
    }
  };

  const columns = [
    {
      title: 'adId',
      dataIndex: 'adId',
      width: 160,
      render: (id: string) => <Link to={`/advertiser/ad/${id}`}>{formatId(id)}</Link>,
    },
    {
      title: '标题',
      dataIndex: 'title',
      render: (t: string, r: AdView) => (
        <Link to={`/advertiser/ad/${r.adId}`}>{t || `#${r.itemId}`}</Link>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: string) => <StatusTag status={s} />,
    },
    {
      title: '审核',
      dataIndex: 'reviewStatus',
      width: 130,
      render: (s: string | null) => <ReviewStatusTag status={s} />,
    },
    {
      title: '计费',
      dataIndex: 'optimizationType',
      width: 90,
      render: (o: string) => <Tag>{o}</Tag>,
    },
    ...(query.data?.some((a) => a.audienceId)
      ? [
          {
            title: '人群',
            dataIndex: 'audienceId',
            width: 90,
            render: (id: string | null) => (id ? <Tag>{id}</Tag> : null),
          },
        ]
      : []),
    { title: '质量度', dataIndex: 'qualityScore', width: 90, render: (v: number) => v?.toFixed(3) },
    {
      title: '向量',
      dataIndex: 'hasEmbedding',
      width: 70,
      render: (b: boolean) => (b ? <Tag color="green">有</Tag> : <Tag>无</Tag>),
    },
    {
      title: '词/创意',
      key: 'cnt',
      width: 90,
      render: (_: unknown, r: AdView) => `${r.bidwords?.length ?? 0} / ${r.creatives?.length ?? 0}`,
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title={`广告主 #${advertiserId} 的广告`}
        accent={ACCENTS.ad}
        extra={
          <Button
            type="primary"
            disabled={!access.can('editAds') || createMut.isPending}
            onClick={() => setOpen(true)}
          >
            新建广告
          </Button>
        }
      />
      <PermissionNotice access={access} />
      <Card>
        {query.isError ? (
          <Alert type="error" showIcon message={toApiError(query.error).message} />
        ) : (
          <Table<AdView>
            rowKey="adId"
            size="small"
            loading={query.isLoading}
            columns={columns}
            dataSource={query.data ?? []}
            pagination={false}
            scroll={{ x: 880 }}
          />
        )}
      </Card>

      <Modal
        title="新建广告"
        open={open}
        onOk={submit}
        okButtonProps={{ disabled: !access.can('editAds') }}
        confirmLoading={createMut.isPending}
        cancelButtonProps={{ disabled: createMut.isPending }}
        closable={!createMut.isPending}
        maskClosable={!createMut.isPending}
        onCancel={() => !createMut.isPending && setOpen(false)}
        destroyOnClose
      >
        <Form
          form={form}
          layout="vertical"
          disabled={createMut.isPending || !access.can('editAds')}
          initialValues={{ optimizationType: 'CPC', status: 'active', qualityScore: 1.0 }}
        >
          <Form.Item
            name="itemId"
            label="关联 itemId(创意/向量来源)"
            rules={[
              { required: true, message: '必填' },
              { pattern: /^[1-9]\d{0,18}$/, message: '请输入完整的正整数 itemId' },
            ]}
          >
            <Input inputMode="numeric" maxLength={19} />
          </Form.Item>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '必填' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="landingUrl" label="落地页 URL">
            <Input placeholder="https://..." />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }} wrap>
            <AdBillingFields />
            <Form.Item name="qualityScore" label="质量度" style={{ flex: 1 }}>
              <InputNumber min={0} step={0.1} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </Space>
  );
}
