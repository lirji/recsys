import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { App, Alert, Button, Card, Select, Space } from 'antd';
import { Link } from 'react-router-dom';
import { getInterests, saveInterests } from '../../api/user';
import { queryKeys } from '../../api/queryKeys';
import { toApiError } from '../../api/client';
import { useGlobalUser } from '../../hooks/useGlobalUser';
import PageHeader from '../../components/PageHeader';
import { ResultRowsSkeleton } from '../../components/Skeletons';
import { ACCENTS } from '../../theme/tokens';

const COMMON = [
  'Action', 'Comedy', 'Drama', 'Thriller', 'Romance', 'Sci-Fi', 'Horror',
  'Animation', 'Documentary', 'Adventure', 'Crime', 'Fantasy', 'Mystery',
  'Children', 'War', 'Musical', 'Western', 'Film-Noir',
];

export default function ColdStartInterests() {
  const { userId } = useGlobalUser();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [cats, setCats] = useState<string[]>([]);

  const query = useQuery({
    queryKey: queryKeys.interests(userId),
    queryFn: () => getInterests(userId),
  });

  useEffect(() => {
    if (query.data) setCats(query.data.categories ?? []);
  }, [query.data]);

  const saveMut = useMutation({
    mutationFn: () => saveInterests(userId, cats),
    onSuccess: async () => {
      message.success('已保存兴趣类目');
      await queryClient.invalidateQueries({ queryKey: queryKeys.interests(userId) });
    },
    onError: (e) => message.error(toApiError(e).message),
  });

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title={`冷启动兴趣 · userId=${userId}`}
        accent={ACCENTS.recall}
        description="写入兴趣类目到画像,冷启动 / TAG 召回会据此引导。"
      />
      <Card style={{ maxWidth: 720 }}>
        {query.isLoading ? (
          <ResultRowsSkeleton rows={3} />
        ) : query.isError ? (
          <Alert type="error" showIcon message={toApiError(query.error).message} />
        ) : (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <span>
              保存后到
              <Link to="/recommend"> 推荐调试台 </Link>
              看结果变化。
            </span>
            <Select
              mode="tags"
              value={cats}
              onChange={setCats}
              style={{ width: '100%' }}
              placeholder="选择或输入兴趣类目"
              options={COMMON.map((c) => ({ value: c, label: c }))}
            />
            <Space>
              <Button type="primary" loading={saveMut.isPending} onClick={() => saveMut.mutate()}>
                保存
              </Button>
              <Button onClick={() => query.refetch()}>重新加载</Button>
            </Space>
          </Space>
        )}
      </Card>
    </Space>
  );
}
