import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
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
  Select,
  Space,
  Spin,
  Steps,
  Tag,
} from 'antd';
import { PictureOutlined, SafetyCertificateOutlined, TagsOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import {
  deleteAd,
  getAd,
  reviewAd,
  setAdStatus,
  submitReview,
  updateAd,
} from '../../api/advertiser';
import { toApiError } from '../../api/client';
import type { AdUpsert, AdView } from '../../api/types';
import { StatusTag } from '../../components/adv/statusTags';
import CreativePanel from '../../components/adv/CreativePanel';
import BidwordPanel from '../../components/adv/BidwordPanel';
import CollapsibleCard from '../../components/CollapsibleCard';
import { ACCENTS } from '../../theme/tokens';

export default function AdDetail() {
  const { adId } = useParams();
  const id = Number(adId);
  const { message } = App.useApp();
  const navigate = useNavigate();
  const query = useQuery({ queryKey: ['ad', id], queryFn: () => getAd(id) });

  const [editOpen, setEditOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [form] = Form.useForm<AdUpsert>();

  useEffect(() => {
    if (query.data)
      form.setFieldsValue({
        title: query.data.title,
        landingUrl: query.data.landingUrl,
        qualityScore: query.data.qualityScore,
        status: query.data.status,
        optimizationType: query.data.optimizationType,
        targetCpa: query.data.targetCpa ?? undefined,
      });
  }, [query.data, form]);

  const act = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn();
      message.success(ok);
      query.refetch();
    } catch (e) {
      message.error(toApiError(e).message);
    }
  };

  const submitEdit = async () => {
    const values = await form.validateFields();
    await act(() => updateAd(id, values), '已更新广告');
    setEditOpen(false);
  };

  if (query.isLoading) return <Spin />;
  if (query.isError) return <Alert type="error" showIcon message={toApiError(query.error).message} />;
  const ad: AdView = query.data!;
  const paused = (ad.status ?? '').toLowerCase() === 'paused';

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title={`广告 #${ad.adId}`}
        style={{ borderLeft: `3px solid ${ACCENTS.recall}` }}
        extra={
          <Space>
            <Button onClick={() => setEditOpen(true)}>编辑</Button>
            <Popconfirm
              title="删除该广告?"
              onConfirm={() => act(() => deleteAd(id).then(() => navigate(`/advertiser/${ad.advertiserId}/ads`)), '已删除')}
            >
              <Button danger>删除</Button>
            </Popconfirm>
          </Space>
        }
      >
        <Descriptions bordered column={2} size="small">
          <Descriptions.Item label="标题">{ad.title}</Descriptions.Item>
          <Descriptions.Item label="状态">
            <StatusTag status={ad.status} />
          </Descriptions.Item>
          <Descriptions.Item label="广告主">{ad.advertiserId}</Descriptions.Item>
          <Descriptions.Item label="itemId">{ad.itemId}</Descriptions.Item>
          <Descriptions.Item label="计费类型">
            <Tag>{ad.optimizationType}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="目标 CPA">{ad.targetCpa ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="质量度">{ad.qualityScore?.toFixed(3)}</Descriptions.Item>
          <Descriptions.Item label="向量">{ad.hasEmbedding ? <Tag color="green">有</Tag> : <Tag>无</Tag>}</Descriptions.Item>
          <Descriptions.Item label="落地页" span={2}>
            {ad.landingUrl || '—'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <CollapsibleCard title="生命周期 / 审核" icon={<SafetyCertificateOutlined />} accent={ACCENTS.rank}>
        <Steps
          size="small"
          style={{ marginBottom: 16 }}
          items={[
            { title: '创建 / 机审' },
            { title: '送审 pending_review' },
            { title: '人审 通过 / 驳回' },
            { title: paused ? '已下线' : '投放中', status: paused ? 'wait' : 'finish' },
          ]}
          current={paused ? 3 : 3}
        />
        <Space wrap>
          <Button onClick={() => act(() => submitReview(id), '已重新送审')}>重新送审</Button>
          <Button type="primary" onClick={() => act(() => reviewAd(id, 'approve'), '已通过')}>
            审核通过
          </Button>
          <Button danger onClick={() => setRejectOpen(true)}>
            驳回
          </Button>
          {paused ? (
            <Button type="primary" ghost onClick={() => act(() => setAdStatus(id, 'active'), '已上线')}>
              上线
            </Button>
          ) : (
            <Button onClick={() => act(() => setAdStatus(id, 'paused'), '已下线')}>下线</Button>
          )}
        </Space>
        <Alert
          style={{ marginTop: 12 }}
          type="info"
          showIcon
          message="在线只服务 approved 广告(倒排/DB 召回/DCO 三处闸门);审核状态未在 AdView 返回,此处步骤为示意,以操作结果为准。"
        />
      </CollapsibleCard>

      <CollapsibleCard title="创意" icon={<PictureOutlined />} accent={ACCENTS.rerank}>
        <CreativePanel adId={id} />
      </CollapsibleCard>
      <CollapsibleCard title="竞价词" icon={<TagsOutlined />} accent={ACCENTS.ad}>
        <BidwordPanel adId={id} />
      </CollapsibleCard>

      <Modal title="编辑广告" open={editOpen} onOk={submitEdit} onCancel={() => setEditOpen(false)}>
        <Form form={form} layout="vertical">
          <Form.Item name="title" label="标题" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="landingUrl" label="落地页 URL">
            <Input />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="optimizationType" label="计费" style={{ flex: 1 }}>
              <Select options={[{ value: 'CPC', label: 'CPC' }, { value: 'OCPC', label: 'OCPC' }]} />
            </Form.Item>
            <Form.Item name="targetCpa" label="目标 CPA" style={{ flex: 1 }}>
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="qualityScore" label="质量度" style={{ flex: 1 }}>
              <InputNumber min={0} step={0.1} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
        </Form>
      </Modal>

      <Modal
        title="驳回广告"
        open={rejectOpen}
        onOk={() => act(() => reviewAd(id, 'reject', reason), '已驳回').then(() => setRejectOpen(false))}
        onCancel={() => setRejectOpen(false)}
      >
        <Input.TextArea rows={3} placeholder="驳回原因(可选)" value={reason} onChange={(e) => setReason(e.target.value)} />
      </Modal>
    </Space>
  );
}
