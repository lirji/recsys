import { expect, test } from '@playwright/test';
// 默认关闭构建开关时的兼容检查；契约响应只验证旧页面调用/展示，不声称真实后端联调。
for (const route of ['recommend', 'search']) {
  test(`自然开关默认关闭保留旧 ${route} 页面`, async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('recsys_token', 'legacy-fixture');
      localStorage.setItem('recsys_username', 'fixture-admin');
      localStorage.setItem('recsys_roles', '["ADMIN"]');
    });
    let legacyCalls = 0,
      sandboxCalls = 0;
    await page.route('**/api/**', async (request) => {
      const path = new URL(request.request().url()).pathname;
      if (path.startsWith('/api/sandbox/')) sandboxCalls++;
      if (path === '/api/' + route) legacyCalls++;
      await request.fulfill({
        json:
          path === '/api/' + route
            ? {
                userId: 1,
                scene: 'feed',
                items: [
                  {
                    itemId: 1,
                    score: 0.9,
                    recallFrom: ['HOT'],
                    reason: '旧页面兼容结果',
                    exposureId: 'legacy-id',
                  },
                ],
                traceId: 'legacy-fixture',
              }
            : path === '/api/console/items'
              ? []
              : {},
      });
    });
    await page.goto('/' + route);
    await expect(page.getByText('旧页面兼容结果', { exact: true })).toBeVisible();
    expect(legacyCalls).toBe(1);
    expect(sandboxCalls).toBe(0);
    await expect(page.getByRole('button', { name: '新交付', exact: true })).toHaveCount(0);
  });
}
