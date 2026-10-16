import { expect, test } from '@playwright/test';

test('缺省构建保留旧搜索广告，关闭两项新入口且不发新协议请求', async ({ page }) => {
  const actions: string[] = [];
  await page.addInitScript(() => {
    localStorage.setItem('recsys_token', 'isolated-default-flags');
    localStorage.setItem('recsys_username', 'fixture-admin');
    localStorage.setItem('recsys_roles', '["ADMIN"]');
  });
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (/\/sandbox\/(click|conversion)-|\/sandbox-conversions\//.test(path)) actions.push(path);
    await route.fulfill({
      json: path === '/api/search-ads' ? { requestId: 'legacy', ads: [] } : {},
    });
  });
  const legacy = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/search-ads');
  await page.goto('/search-ads');
  expect((await legacy).status()).toBe(200);
  await expect(page.getByText('CPC / OCPC Sandbox', { exact: true })).toHaveCount(0);
  await expect(page.getByText('CPA Sandbox', { exact: true })).toHaveCount(0);
  await page.goto('/advertiser/1');
  await expect(page.getByText('CPA Sandbox 业务事件', { exact: true })).toHaveCount(0);
  expect(actions).toEqual([]);
});
