import { http } from './client';
import type { UserProfileView } from './types';

// 用户360:静态画像 + 行为聚合 + 向量存在性 + 在线实时态(Redis)。DB/Redis 任一挂则对应部分降级。
export async function getUserProfile(userId: number): Promise<UserProfileView> {
  const { data } = await http.get<UserProfileView>(`/api/console/user/${userId}`);
  return data;
}
