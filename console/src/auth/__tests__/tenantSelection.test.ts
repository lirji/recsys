import { describe, expect, it } from 'vitest';
import { validateTenantSelection } from '../tenantSelection';

describe('Recsys OIDC 租户选择', () => {
  const clientId = 'ragshared0client00000001-org-recsys';

  it('只接受当前配置的唯一租户，并允许首尾空格', () => {
    expect(validateTenantSelection(' recsys ', 'recsys', clientId)).toEqual({
      ok: true,
      organization: 'recsys',
    });
    expect(validateTenantSelection('acme', 'recsys', clientId)).toEqual({
      ok: false,
      message: '当前推荐系统只开放租户 recsys',
    });
  });

  it('空输入与空 organization 都 fail-closed', () => {
    expect(validateTenantSelection('', 'recsys', clientId)).toEqual({
      ok: false,
      message: '请输入租户',
    });
    expect(validateTenantSelection('recsys', '', clientId)).toEqual({
      ok: false,
      message: 'OIDC 租户配置为空，请联系管理员',
    });
  });

  it('clientId 的 organization 后缀不一致时拒绝跳转', () => {
    expect(
      validateTenantSelection('recsys', 'recsys', 'ragshared0client00000001-org-acme'),
    ).toEqual({
      ok: false,
      message: 'OIDC clientId 与租户配置不一致，请联系管理员',
    });
  });
});
