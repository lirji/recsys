import { useEffect, useState } from 'react';
import { App } from 'antd';
import { useGlobalUser } from './useGlobalUser';
import { useUrlParams } from './useUrlParams';

/**
 * 调试台共用:URL 深链 + applied 快照 + 顶栏 userId/scene 一次性同步 + 分享链接。
 * 页面仍自管草稿字段(size/q),点「运行」时 setApplied。
 */
export function useDebugParams<T extends Record<string, string | number>>(defaults: T) {
  const { userId, scene, setUserId, setScene } = useGlobalUser();
  const { message } = App.useApp();
  const seeded = {
    ...defaults,
    ...('userId' in defaults ? { userId } : {}),
    ...('scene' in defaults ? { scene } : {}),
  } as T;
  const { initial, write } = useUrlParams<T>(seeded);
  const [applied, setApplied] = useState<T>(initial);

  useEffect(() => {
    const init = initial as T & { userId?: number; scene?: string };
    if (typeof init.userId === 'number' && init.userId !== userId) setUserId(init.userId);
    if (typeof init.scene === 'string' && init.scene !== scene) setScene(init.scene);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    write(applied);
  }, [applied, write]);

  const shareLink = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href);
      message.success('已复制分享链接(含当前参数)');
    } catch {
      message.error('复制失败');
    }
  };

  return { initial, applied, setApplied, shareLink, userId, scene, setUserId, setScene, message };
}
