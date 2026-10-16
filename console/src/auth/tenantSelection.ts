import { formatError } from '../api/errors';
import type { KnownErrorCode } from '../api/generated/errorCodes';

export type TenantSelectionResult =
  { ok: true; organization: string } | { ok: false; code: KnownErrorCode; message: string };

/**
 * Recsys 目前只有一个经过网关与数据边界验证的 Casdoor organization。
 * 登录页允许用户确认租户，但绝不把任意输入拼成 clientId，以免伪装成已支持真实多租户。
 */
export function validateTenantSelection(
  rawTenant: string,
  expectedOrganization: string,
  configuredClientId: string,
): TenantSelectionResult {
  const tenant = rawTenant.trim();
  const organization = expectedOrganization.trim();

  if (!tenant) return rejected('OIDC_TENANT_REQUIRED');
  if (!organization) return rejected('OIDC_CONFIGURATION_INVALID');
  if (tenant !== organization) {
    return rejected('OIDC_TENANT_NOT_ALLOWED');
  }
  if (!configuredClientId.endsWith(`-org-${organization}`)) {
    return rejected('OIDC_CONFIGURATION_INVALID');
  }

  return { ok: true, organization };
}

function rejected(code: KnownErrorCode): TenantSelectionResult {
  return { ok: false, code, message: formatError(code) };
}
