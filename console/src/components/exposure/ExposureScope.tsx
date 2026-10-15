import {
  createContext,
  useContext,
  useEffect,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { Alert } from 'antd';
import { useAuth } from '../../hooks/useAuth';
import { resolveToken } from '../../auth/oidc';
import type { ExposureIdentity } from '../../api/exposure';
import { exposureAuthScope, exposureEpoch, onExposureRevoked } from '../../exposure/identity';
import { receiptQueue } from '../../exposure/receiptQueue';

const Context = createContext<ExposureIdentity | null>(null);
/** 页面显式选择采集；身份尚未恢复、切换或退出时没有可发送的 scope。 */
interface ScopeQueue {
  subscribe: (listener: () => void) => () => void;
  storageAvailable: boolean;
  bind: (identity: ExposureIdentity) => void;
}
export default function ExposureScope({
  children,
  queue = receiptQueue(),
}: {
  children: ReactNode;
  queue?: ScopeQueue;
}) {
  const { user, ready, switching } = useAuth();
  const [identity, setIdentity] = useState<ExposureIdentity | null>(null);
  const storageAvailable = useSyncExternalStore(queue.subscribe, () => queue.storageAvailable);
  useEffect(() => onExposureRevoked(() => setIdentity(null)), []);
  useEffect(() => {
    let disposed = false;
    const epoch = exposureEpoch();
    if (!ready || switching || !user?.roles.includes('ADMIN')) {
      setIdentity(null);
      return;
    }
    void resolveToken()
      .then((token) => {
        if (disposed || epoch !== exposureEpoch()) return;
        const scope = exposureAuthScope(token);
        if (!scope) {
          setIdentity(null);
          return;
        }
        const next = { scope, epoch };
        queue.bind(next);
        setIdentity(next);
      })
      .catch(() => {
        if (!disposed) setIdentity(null);
      });
    return () => {
      disposed = true;
    };
  }, [user, ready, switching, queue]);
  return (
    <Context.Provider value={identity}>
      {!storageAvailable && (
        <Alert
          type="warning"
          showIcon
          message="浏览器会话存储不可用"
          description="当前可在内存中发送回执，刷新或关闭后无法恢复未确认结果。"
        />
      )}
      {children}
    </Context.Provider>
  );
}
export const useExposureScope = () => useContext(Context);
