import { describe, expect, it } from 'vitest';
import { validateTenantSelection } from '../tenantSelection';
import { formatError } from '../../api/errors';

describe('Recsys OIDC 租户选择', () => {
  const clientId = 'ragshared0client00000001-org-recsys';

  it('只接受当前配置的唯一租户，并允许首尾空格', () => {
    expect(validateTenantSelection(' recsys ', 'recsys', clientId)).toEqual({
      ok: true,
      organization: 'recsys',
    });
    expect(validateTenantSelection('acme', 'recsys', clientId)).toEqual({
      ok: false,
      code: 'OIDC_TENANT_NOT_ALLOWED',
      message: formatError('OIDC_TENANT_NOT_ALLOWED'),
    });
  });

  it('空输入与空 organization 都 fail-closed', () => {
    expect(validateTenantSelection('', 'recsys', clientId)).toEqual({
      ok: false,
      code: 'OIDC_TENANT_REQUIRED',
      message: formatError('OIDC_TENANT_REQUIRED'),
    });
    expect(validateTenantSelection('recsys', '', clientId)).toEqual({
      ok: false,
      code: 'OIDC_CONFIGURATION_INVALID',
      message: formatError('OIDC_CONFIGURATION_INVALID'),
    });
  });

  it('clientId 的 organization 后缀不一致时拒绝跳转', () => {
    expect(
      validateTenantSelection('recsys', 'recsys', 'ragshared0client00000001-org-acme'),
    ).toEqual({
      ok: false,
      code: 'OIDC_CONFIGURATION_INVALID',
      message: formatError('OIDC_CONFIGURATION_INVALID'),
    });
  });
});
