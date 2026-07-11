import { http } from './client';
import type { AlertItem } from './types';

// 告警面板:从服务健康 / 数据质量报表 / 链路延迟派生的当前告警列表(ERROR→WARN→INFO)。
export async function getAlerts(): Promise<AlertItem[]> {
  const { data } = await http.get<AlertItem[]>('/api/console/alerts');
  return data;
}
