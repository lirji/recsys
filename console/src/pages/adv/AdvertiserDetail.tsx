import { SANDBOX_CONVERSION_ENABLED } from '../../config/sandboxActions';
import SandboxConversionPanel from '../../components/adv/SandboxConversionPanel';
import { showApiError } from '../../api/errors';
import { useAdvertiserPermissions } from '../../hooks/useAdvertiserPermissions';
import PermissionNotice from '../../components/adv/PermissionNotice';
import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  App,
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
} from 'antd';
import { Link, useParams } from 'react-router-dom';
import { getAdvertiser, updateAdvertiser } from '../../api/advertiser';
import { queryKeys } from '../../api/queryKeys';
import { toApiError } from '../../api/client';
import type { AdvertiserUpsert } from '../../api/types';
import { StatusTag } from '../../components/adv/statusTags';
import PageHeader from '../../components/PageHeader';
import { ResultRowsSkeleton } from '../../components/Skeletons';
import { ACCENTS } from '../../theme/tokens';
import { formatBudgetEstimate } from '../../utils/adPricing';

export default function AdvertiserDetail() {
  const { id } = useParams();
  const advertiserId = id ?? '';
  const { message } = App.useApp();
  const access = useAdvertiserPermissions(advertiserId);
  const client = useQueryClient();
  const inFlight = useRef(false);
  const query = useQuery({
    queryKey: queryKeys.advertiser(advertiserId),
    queryFn: () => getAdvertiser(advertiserId),
  });
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<AdvertiserUpsert>();

  const openEdit = () => {
    if (!query.data) return;
    form.resetFields();
    form.setFieldsValue({
      name: query.data.name,
      dailyBudget: query.data.dailyBudget,
      status: query.data.status,
    });
    setOpen(true);
  };

  const submit = async () => {
    if (!access.can('manageAdvertiser') || inFlight.current) return;
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
      await updateAdvertiser(advertiserId, values);
      message.success('已更新');
      setOpen(false);
      await query.refetch();
      await client.invalidateQueries({ queryKey: queryKeys.advertisers() });
    } catch (e) {
      showApiError(e, message.error);
      void access.query.refetch();
    } finally {
      inFlight.current = false;
      setSaving(false);
    }
  };

  if (query.isLoading) return <ResultRowsSkeleton rows={3} />;
  if (query.isError)
    return <Alert type="error" showIcon message={toApiError(query.error).message} />;
  const a = query.data!;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader title={`广告主 #${a.advertiserId}`} accent={ACCENTS.ad} description={a.name} />
      <PermissionNotice access={access} />
      {SANDBOX_CONVERSION_ENABLED && <SandboxConversionPanel advertiserId={advertiserId} />}
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
            <Button
              type="primary"
              disabled={!access.can('manageAdvertiser') || saving}
              onClick={openEdit}
            >
              编辑
            </Button>
          </Space>
        }
      >
        <Descriptions bordered column={{ xs: 1, sm: 2 }} size="small">
          <Descriptions.Item label="名称">{a.name}</Descriptions.Item>
          <Descriptions.Item label="状态">
            <StatusTag status={a.status} />
          </Descriptions.Item>
          <Descriptions.Item label="日预算">{a.dailyBudget?.toFixed(2)}</Descriptions.Item>
          <Descriptions.Item label="今日消费（计数估计·元）">
            {formatBudgetEstimate(a.spentToday)}
          </Descriptions.Item>
          <Descriptions.Item label="剩余预算（计数估计·元）">
            {formatBudgetEstimate(a.remainingBudget)}
          </Descriptions.Item>
        </Descriptions>

        <Modal
          title="编辑广告主"
          open={open}
          onOk={submit}
          okButtonProps={{ disabled: !access.can('manageAdvertiser') }}
          confirmLoading={saving}
          cancelButtonProps={{ disabled: saving }}
          closable={!saving}
          maskClosable={!saving}
          onCancel={() => !saving && setOpen(false)}
        >
          <Form form={form} layout="vertical" disabled={saving || !access.can('manageAdvertiser')}>
            <Form.Item name="name" label="名称" rules={[{ required: true }]}>
              <Input />
            </Form.Item>
            <Form.Item name="dailyBudget" label="日预算">
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="status" label="状态">
              <Select
                options={[
                  { value: 'active', label: 'active' },
                  { value: 'paused', label: 'paused' },
                ]}
              />
            </Form.Item>
          </Form>
        </Modal>
      </Card>
    </Space>
  );
}
