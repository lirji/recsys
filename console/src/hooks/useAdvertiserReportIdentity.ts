import { useEffect, useState, useSyncExternalStore } from 'react';
import { useAuth } from './useAuth';
import { resolveToken } from '../auth/oidc';
import { exposureAuthScope, exposureEpoch, onExposureRevoked } from '../exposure/identity';
import type { ExposureIdentity } from '../api/exposure';

/** 报表仅借用纯身份作用域，不绑定曝光/转化队列或浏览器持久化业务意图。 */
export function useAdvertiserReportIdentity(): ExposureIdentity | null {
  const { user, ready, switching } = useAuth();
  const epoch = useSyncExternalStore(onExposureRevoked, exposureEpoch);
  const [resolved, setResolved] = useState<{
    identity: ExposureIdentity;
    user: typeof user;
  } | null>(null);
  const allowed =
    ready && !switching && !!user?.roles.some((role) => role === 'ADMIN' || role === 'ADVERTISER');
  useEffect(() => {
    let disposed = false;
    if (!allowed) {
      setResolved(null);
      return;
    }
    void resolveToken()
      .then((token) => {
        if (disposed || epoch !== exposureEpoch()) return;
        const scope = exposureAuthScope(token);
        setResolved(scope ? { identity: { scope, epoch }, user } : null);
      })
      .catch(() => {
        if (!disposed) setResolved(null);
      });
    return () => {
      disposed = true;
    };
  }, [allowed, user, epoch]);
  return allowed && resolved?.user === user && resolved.identity.epoch === epoch
    ? resolved.identity
    : null;
}
