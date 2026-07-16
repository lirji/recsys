import { describe, expect, it, vi, beforeEach } from 'vitest';

// 统一 mock 到 oidc 模式;UserManager 用假实现(不真加载 oidc-client-ts 的浏览器流程)。
const mocks = vi.hoisted(() => ({
  mode: 'oidc' as string,
  getUser: vi.fn(),
}));

vi.mock('../../config/auth', () => ({
  get AUTH_MODE() {
    return mocks.mode;
  },
  CASDOOR: { issuer: 'http://localhost:8000', clientId: 'base-org-recsys', scope: 'openid' },
}));

vi.mock('oidc-client-ts', () => ({
  UserManager: class {
    getUser = mocks.getUser;
  },
  WebStorageStateStore: class {},
}));

import { decodeJwtPayload, resolveToken, sanitizeReturnTo, userFromCasdoorToken } from '../oidc';

/** 造未签名 JWT(payload base64url;网关才验签,前端只解 payload)。 */
function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = btoa(JSON.stringify(payload))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `h.${b64}.sig`;
}

describe('decodeJwtPayload', () => {
  it('正常 payload 可解;坏 token / 空值 → null', () => {
    expect(decodeJwtPayload(fakeJwt({ name: 'a' }))).toEqual({ name: 'a' });
    expect(decodeJwtPayload('not-a-jwt')).toBeNull();
    expect(decodeJwtPayload('a.%%%.c')).toBeNull();
    expect(decodeJwtPayload(null)).toBeNull();
    expect(decodeJwtPayload('')).toBeNull();
  });
});

describe('userFromCasdoorToken(groups→角色,与网关映射同源)', () => {
  it('admins→ADMIN、advertisers→ADVERTISER,全路径取短名', () => {
    expect(userFromCasdoorToken(fakeJwt({ name: 'radmin', groups: ['recsys/admins'] }))).toEqual({
      username: 'radmin',
      roles: ['ADMIN'],
    });
    expect(
      userFromCasdoorToken(fakeJwt({ name: 'rowner1', groups: ['recsys/advertisers'] })),
    ).toEqual({
      username: 'rowner1',
      roles: ['ADVERTISER'],
    });
  });

  it('未映射组 / 无组 → USER 兜底(收敛三值枚举,防 ROLE_META 渲染崩溃)', () => {
    expect(userFromCasdoorToken(fakeJwt({ name: 'x', groups: ['recsys/unknown'] }))?.roles).toEqual(
      ['USER'],
    );
    expect(userFromCasdoorToken(fakeJwt({ name: 'x' }))?.roles).toEqual(['USER']);
  });

  it('解不出用户名 / 坏 token → null(不建会话,幽灵登录防护)', () => {
    expect(userFromCasdoorToken(fakeJwt({ groups: ['recsys/admins'] }))).toBeNull();
    expect(userFromCasdoorToken('garbage')).toBeNull();
    expect(userFromCasdoorToken(null)).toBeNull();
  });
});

describe('sanitizeReturnTo(开放重定向防护)', () => {
  it('站内绝对路径通过(含 query/hash)', () => {
    expect(sanitizeReturnTo('/advertiser/5?tab=ads#top')).toBe('/advertiser/5?tab=ads#top');
  });
  it('协议相对 / 外站 / 非串 → null', () => {
    expect(sanitizeReturnTo('//evil.com')).toBeNull();
    expect(sanitizeReturnTo('http://evil.com')).toBeNull();
    expect(sanitizeReturnTo('javascript:alert(1)')).toBeNull();
    expect(sanitizeReturnTo(undefined)).toBeNull();
    expect(sanitizeReturnTo(42)).toBeNull();
  });
});

describe('resolveToken(模式分派,R1:oidc 绝不读 legacy localStorage)', () => {
  beforeEach(() => {
    localStorage.clear();
    mocks.getUser.mockReset();
  });

  it('oidc:读 oidc 存储的未过期 token;localStorage 残留 legacy token 不误发', async () => {
    localStorage.setItem('recsys_token', 'stale-legacy-hs256'); // 残留
    mocks.mode = 'oidc';
    mocks.getUser.mockResolvedValue({ expired: false, access_token: 'casdoor-rs256' });
    expect(await resolveToken()).toBe('casdoor-rs256');

    mocks.getUser.mockResolvedValue(null); // 无 oidc 会话
    expect(await resolveToken()).toBeNull(); // ← 不回退 legacy token
  });

  it('oidc:过期会话不带 token(交给 401 单飞续期)', async () => {
    mocks.mode = 'oidc';
    mocks.getUser.mockResolvedValue({ expired: true, access_token: 'expired' });
    expect(await resolveToken()).toBeNull();
  });

  it('legacy:读 localStorage', async () => {
    mocks.mode = 'legacy';
    localStorage.setItem('recsys_token', 'legacy-token');
    expect(await resolveToken()).toBe('legacy-token');
  });
});
