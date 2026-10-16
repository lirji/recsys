import { showApiError, toApiError } from '../../api/errors';
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
  Popconfirm,
  Space,
  Tag,
} from 'antd';
import { PictureOutlined, SafetyCertificateOutlined, TagsOutlined } from '@ant-design/icons';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  deleteAd,
  getAd,
  reviewAd,
  setAdStatus,
  submitReview,
  updateAd,
} from '../../api/advertiser';
import { queryKeys } from '../../api/queryKeys';
import type { AdUpsert, AdvertiserPermissions } from '../../api/types';
import { StatusTag, ReviewStatusTag } from '../../components/adv/statusTags';
import AdBillingFields from '../../components/adv/AdBillingFields';
import CreativePanel from '../../components/adv/CreativePanel';
import BidwordPanel from '../../components/adv/BidwordPanel';
import PermissionNotice from '../../components/adv/PermissionNotice';
import { useAdvertiserPermissions } from '../../hooks/useAdvertiserPermissions';
import CollapsibleCard from '../../components/CollapsibleCard';
import PageHeader from '../../components/PageHeader';
import { ResultRowsSkeleton } from '../../components/Skeletons';
import { ACCENTS } from '../../theme/tokens';

export default function AdDetail() {
  const { adId = '' } = useParams();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const client = useQueryClient();
  const query = useQuery({ queryKey: queryKeys.ad(adId), queryFn: () => getAd(adId) });
  const ad = query.data;
  const access = useAdvertiserPermissions(ad?.advertiserId, !!ad);
  const [editOpen, setEditOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [form] = Form.useForm<AdUpsert>();
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);

  // 只有已确认成功才关闭弹窗；锁也覆盖同一事件循环内的重复点击。
  const act = async (
    permission: keyof AdvertiserPermissions,
    fn: () => Promise<unknown>,
    ok: string,
    deleted = false,
  ) => {
    if (inFlight.current || !access.can(permission)) return false;
    inFlight.current = true;
    setBusy(true);
    try {
      await fn();
      message.success(ok);
      if (deleted) navigate(`/advertiser/${ad!.advertiserId}/ads`);
      else await client.invalidateQueries({ queryKey: queryKeys.ad(adId) });
      await client.invalidateQueries({ queryKey: queryKeys.ads(ad!.advertiserId) });
      return true;
    } catch (error) {
      showApiError(error, message.error);
      void access.query.refetch();
      return false;
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  };

  const submitEdit = async () => {
    try {
      const values = await form.validateFields();
      if (
        await act(
          'editAds',
          () => updateAd(adId, { ...values, audienceId: values.audienceId || '0' }),
          '已更新广告',
        )
      ) {
        setEditOpen(false);
      }
    } catch {
      /* AntD 将字段校验错误保留在表单，不触发接口或关闭弹窗。 */
    }
  };

  if (query.isLoading) return <ResultRowsSkeleton rows={5} />;
  if (query.isError)
    return (
      <Alert
        type="error"
        showIcon
        message={toApiError(query.error).message}
        action={<Button onClick={() => query.refetch()}>重试</Button>}
      />
    );
  if (!ad) return null;
  const paused = ad.status === 'paused';
  const canEdit = access.can('editAds');
  const canReview = access.can('reviewAds');
  const reviewKnown = ['approved', 'pending_review', 'rejected'].includes(ad.reviewStatus ?? '');

  const openEdit = () => {
    // 只在用户打开时取快照，后台刷新不能覆盖尚未保存的输入。
    form.resetFields();
    form.setFieldsValue({
      title: ad.title,
      landingUrl: ad.landingUrl,
      qualityScore: ad.qualityScore,
      optimizationType: ad.optimizationType,
      targetCpa: ad.targetCpa ?? undefined,
      audienceId: ad.audienceId ?? undefined,
    });
    setEditOpen(true);
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title={`广告 #${ad.adId}`}
        accent={ACCENTS.ad}
        description={ad.title || '广告详情 / 审核 / 创意 / 竞价词'}
      />
      <PermissionNotice access={access} />
      <Card
        title={`广告 #${ad.adId}`}
        style={{ borderLeft: `3px solid ${ACCENTS.recall}` }}
        extra={
          <Space wrap>
            <Button disabled={!canEdit || busy} onClick={openEdit}>
              编辑
            </Button>
            <Popconfirm
              title="删除该广告?"
              disabled={!canEdit || busy}
              onConfirm={() => act('editAds', () => deleteAd(adId), '已删除', true)}
            >
              <Button danger disabled={!canEdit || busy}>
                删除
              </Button>
            </Popconfirm>
          </Space>
        }
      >
        <Descriptions bordered column={{ xs: 1, sm: 2 }} size="small">
          <Descriptions.Item label="标题">{ad.title}</Descriptions.Item>
          <Descriptions.Item label="启停状态">
            <StatusTag status={ad.status} />
          </Descriptions.Item>
          <Descriptions.Item label="广告主">
            <Link to={`/advertiser/${ad.advertiserId}`}>{ad.advertiserId}</Link>
          </Descriptions.Item>
          <Descriptions.Item label="itemId">{ad.itemId}</Descriptions.Item>
          <Descriptions.Item label="计费类型">
            <Tag>{ad.optimizationType}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="目标 CPA">{ad.targetCpa ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="定向人群">{ad.audienceId ?? '不定向'}</Descriptions.Item>
          <Descriptions.Item label="质量度">{ad.qualityScore?.toFixed(3)}</Descriptions.Item>
          <Descriptions.Item label="向量">
            {ad.hasEmbedding ? <Tag color="green">有</Tag> : <Tag>无</Tag>}
          </Descriptions.Item>
          <Descriptions.Item label="落地页" span={2}>
            {ad.landingUrl || '—'}
          </Descriptions.Item>
        </Descriptions>
      </Card>
      <CollapsibleCard
        title="生命周期 / 审核"
        icon={<SafetyCertificateOutlined />}
        accent={ACCENTS.rank}
      >
        <Descriptions column={1} size="small" style={{ marginBottom: 16 }}>
          <Descriptions.Item label="审核状态">
            <ReviewStatusTag status={ad.reviewStatus} />
          </Descriptions.Item>
          <Descriptions.Item label="审核说明">
            {ad.reviewReason || '暂无审核说明'}
          </Descriptions.Item>
        </Descriptions>
        <Space wrap>
          <Button
            disabled={!canEdit || busy}
            onClick={() => act('editAds', () => submitReview(adId), '已重新送审')}
          >
            重新送审
          </Button>
          <Button
            type="primary"
            disabled={!canReview || busy || !reviewKnown || ad.reviewStatus === 'approved'}
            onClick={() => act('reviewAds', () => reviewAd(adId, 'approve'), '审核已通过')}
          >
            审核通过
          </Button>
          <Button
            danger
            disabled={!canReview || busy || !reviewKnown || ad.reviewStatus === 'rejected'}
            onClick={() => {
              setReason('');
              setRejectOpen(true);
            }}
          >
            驳回
          </Button>
          <Button
            disabled={!canEdit || busy || (!paused && ad.status !== 'active')}
            onClick={() =>
              act(
                'editAds',
                () => setAdStatus(adId, paused ? 'active' : 'paused'),
                paused ? '广告已启用' : '广告已暂停',
              )
            }
          >
            {paused ? '启用' : '暂停'}
          </Button>
        </Space>
        <Alert
          style={{ marginTop: 12 }}
          type="info"
          showIcon
          message="启用与审核分别生效。只有审核通过的广告才可能参与投放，实际展示还受广告主状态、预算和竞价条件影响。"
        />
      </CollapsibleCard>
      <CollapsibleCard title="创意" icon={<PictureOutlined />} accent={ACCENTS.rerank}>
        <CreativePanel adId={adId} advertiserId={ad.advertiserId} canEdit={canEdit && !busy} />
      </CollapsibleCard>
      <CollapsibleCard title="竞价词" icon={<TagsOutlined />} accent={ACCENTS.ad}>
        <BidwordPanel adId={adId} advertiserId={ad.advertiserId} canEdit={canEdit && !busy} />
      </CollapsibleCard>
      <Modal
        title="编辑广告"
        open={editOpen}
        onOk={submitEdit}
        confirmLoading={busy}
        okButtonProps={{ disabled: !canEdit }}
        cancelButtonProps={{ disabled: busy }}
        closable={!busy}
        maskClosable={!busy}
        onCancel={() => !busy && setEditOpen(false)}
      >
        <Form form={form} layout="vertical" disabled={busy || !canEdit}>
          <Form.Item name="title" label="标题" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="landingUrl" label="落地页 URL">
            <Input />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }} wrap>
            <AdBillingFields />
            <Form.Item name="qualityScore" label="质量度" style={{ flex: 1 }}>
              <InputNumber min={0} step={0.1} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
      <Modal
        title="驳回广告"
        open={rejectOpen}
        confirmLoading={busy}
        okButtonProps={{ disabled: !canReview }}
        cancelButtonProps={{ disabled: busy }}
        closable={!busy}
        maskClosable={!busy}
        onOk={async () => {
          if (await act('reviewAds', () => reviewAd(adId, 'reject', reason), '已驳回'))
            setRejectOpen(false);
        }}
        onCancel={() => !busy && setRejectOpen(false)}
      >
        <Input.TextArea
          aria-label="驳回原因"
          rows={3}
          placeholder="驳回原因(可选)"
          value={reason}
          disabled={busy || !canReview}
          onChange={(e) => setReason(e.target.value)}
        />
      </Modal>
    </Space>
  );
}
