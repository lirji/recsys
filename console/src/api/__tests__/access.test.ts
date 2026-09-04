import { describe, expect, it } from 'vitest';
import { canAccess, hasAnyRole } from '../access';
import { navGroupForPath, showsDebugContext } from '../nav';

describe('canAccess / hasAnyRole', () => {
  it('无 roles 限制 → 全员可见', () => {
    expect(canAccess({}, ['USER'])).toBe(true);
    expect(canAccess({ roles: [] }, ['USER'])).toBe(true);
  });

  it('按角色过滤菜单', () => {
    expect(canAccess({ roles: ['ADMIN'] }, ['USER'])).toBe(false);
    expect(canAccess({ roles: ['ADMIN', 'ADVERTISER'] }, ['ADVERTISER'])).toBe(true);
    expect(hasAnyRole(['USER'], ['ADMIN'])).toBe(false);
    expect(hasAnyRole(['ADMIN'], ['ADMIN', 'ADVERTISER'])).toBe(true);
  });
});

describe('navGroupForPath / showsDebugContext', () => {
  it('详情子路由归到广告平台,不显示顶栏 userId', () => {
    expect(navGroupForPath('/advertiser/1/ads')).toBe('广告平台');
    expect(showsDebugContext('/advertiser/1/ads')).toBe(false);
    expect(showsDebugContext('/experiment')).toBe(false);
    expect(showsDebugContext('/ops')).toBe(false);
  });

  it('在线调试台显示顶栏 userId/scene', () => {
    expect(navGroupForPath('/search-ads')).toBe('在线链路');
    expect(showsDebugContext('/search-ads')).toBe(true);
    expect(showsDebugContext('/recall-lab')).toBe(true);
  });
});
