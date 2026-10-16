import { useQuery } from '@tanstack/react-query';
import { getAdvertiserPermissions } from '../api/advertiser';
import type { AdvertiserPermissions } from '../api/types';
import { hasAnyRole } from '../api/access';
import { useAuth } from './useAuth';

/** 每个身份/对象使用独立快照；获取新权限期间不沿用旧写权限。 */
export function useAdvertiserPermissions(advertiserId?: string, enabled = true) {
  const { user, ready, switching } = useAuth();
  const active = enabled && ready && !switching && hasAnyRole(user?.roles, ['ADMIN', 'ADVERTISER']);
  const query = useQuery({
    queryKey: [
      'advertiser-permissions',
      user?.username,
      user?.roles.join(','),
      advertiserId ?? 'platform',
    ],
    queryFn: ({ signal }) => getAdvertiserPermissions(advertiserId, signal),
    enabled: active,
    staleTime: 0,
    refetchInterval: 30_000,
    retry: false,
  });
  const settled = active && !query.isPending && !query.isFetching && !query.isError;
  return {
    query,
    can: (action: keyof AdvertiserPermissions) => settled && query.data?.[action] === true,
  };
}
