export type TenantSelectionResult =
  { ok: true; organization: string } | { ok: false; message: string };

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

  if (!tenant) return { ok: false, message: '请输入租户' };
  if (!organization) return { ok: false, message: 'OIDC 租户配置为空，请联系管理员' };
  if (tenant !== organization) {
    return { ok: false, message: `当前推荐系统只开放租户 ${organization}` };
  }
  if (!configuredClientId.endsWith(`-org-${organization}`)) {
    return { ok: false, message: 'OIDC clientId 与租户配置不一致，请联系管理员' };
  }

  return { ok: true, organization };
}
