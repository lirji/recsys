import { http } from './client';
import type { UserProfileView } from './types';

// 用户360:静态画像 + 行为聚合 + 向量存在性 + 在线实时态(Redis)。DB/Redis 任一不可读时由errors标明分区，未知标量为null，成功部分仍可展示。
export async function getUserProfile(userId: number): Promise<UserProfileView> {
  const { data } = await http.get<UserProfileView>(`/api/console/user/${userId}`);
  return data;
}
