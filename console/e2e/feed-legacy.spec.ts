import { expect, test } from '@playwright/test';

// 契约 fixture 仅证明默认开关与旧展示兼容；真实混排账务由 feed 专项验收。
test('混排开关默认关闭保留旧 feed 调用与展示', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('recsys_token', 'legacy-fixture');
    localStorage.setItem('recsys_username', 'fixture-admin');
    localStorage.setItem('recsys_roles', '["ADMIN"]');
  });
  let legacyCalls = 0;
  let sandboxCalls = 0;
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/feed') legacyCalls++;
    if (path.startsWith('/api/sandbox/')) sandboxCalls++;
    await route.fulfill({
      json:
        path === '/api/feed'
          ? {
              entries: [
                {
                  position: 1,
                  ad: false,
                  itemId: 1,
                  score: 0.9,
                  recallFrom: ['HOT'],
                  reason: '旧混排兼容结果',
                },
              ],
              traceId: 'legacy-feed-fixture',
            }
          : path === '/api/console/items'
            ? []
            : {},
    });
  });
  await page.goto('/feed');
  await expect(page.getByText('旧混排兼容结果', { exact: true })).toBeVisible();
  expect(legacyCalls).toBe(1);
  expect(sandboxCalls).toBe(0);
  await expect(page.getByRole('button', { name: '新交付', exact: true })).toHaveCount(0);
});
