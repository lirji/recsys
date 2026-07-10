import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { App, Alert, Button, Card, Select, Space, Spin, Typography } from 'antd';
import { Link } from 'react-router-dom';
import { getInterests, saveInterests } from '../../api/user';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';

const COMMON = [
  'Action', 'Comedy', 'Drama', 'Thriller', 'Romance', 'Sci-Fi', 'Horror',
  'Animation', 'Documentary', 'Adventure', 'Crime', 'Fantasy', 'Mystery',
  'Children', 'War', 'Musical', 'Western', 'Film-Noir',
];

export default function ColdStartInterests() {
  const { userId } = useGlobalUser();
  const { message } = App.useApp();
  const [cats, setCats] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);

  const query = useQuery({
    queryKey: ['interests', userId],
    queryFn: () => getInterests(userId),
  });

  useEffect(() => {
    if (query.data) setCats(query.data.categories ?? []);
  }, [query.data]);

  const save = async () => {
    setSaving(true);
    try {
      await saveInterests(userId, cats);
      message.success('已保存兴趣类目');
      query.refetch();
    } catch (e) {
      message.error(toApiError(e).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card title={`冷启动兴趣 · userId=${userId}`} style={{ maxWidth: 720 }}>
      {query.isLoading ? (
        <Spin />
      ) : query.isError ? (
        <Alert type="error" showIcon message={toApiError(query.error).message} />
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Typography.Text type="secondary">
            为该用户写入兴趣类目(写画像),冷启动/TAG 召回会据此引导。保存后到
            <Link to="/recommend"> 推荐调试台 </Link>
            看结果变化。
          </Typography.Text>
          <Select
            mode="tags"
            value={cats}
            onChange={setCats}
            style={{ width: '100%' }}
            placeholder="选择或输入兴趣类目"
            options={COMMON.map((c) => ({ value: c, label: c }))}
          />
          <Space>
            <Button type="primary" loading={saving} onClick={save}>
              保存
            </Button>
            <Button onClick={() => query.refetch()}>重新加载</Button>
          </Space>
        </Space>
      )}
    </Card>
  );
}
