import { expect, test } from '@playwright/test';

test('login page renders without backend', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('button', { name: /使用统一身份登录|登录/ })).toBeVisible();
});
