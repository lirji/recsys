import { http } from './client';
import type { ActionType, BehaviorEvent } from './types';

export async function reportBehavior(e: BehaviorEvent): Promise<void> {
  await http.post('/api/behavior', e);
}

// 便捷:上报一条针对某 item 的行为(时间戳由前端补)。ts 用 performance.timeOrigin+now 也可,这里用 Date.now()。
export function makeEvent(
  userId: number,
  itemId: number,
  action: ActionType,
  scene: string,
  value = 1.0,
): BehaviorEvent {
  return { userId, itemId, action, value, scene, ts: Date.now() };
}
