import { expect, test, type Page } from '@playwright/test';

// 全部请求在浏览器边界隔离；只验证真实页面对响应的处理，不写共享Redis或数据库。
const privateDetail = 'PRIVATE_SQL_PASSWORD_STACK';
const snapshot = {
  staticEnabled: true,
  enabled: true,
  staticLayers: { recall: { enabled: true, variants: { base: 50, trial: 50 } } },
  overrides: {},
};

async function isolatedAdmin(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('recsys_token', 'isolated-error-fixture');
    localStorage.setItem('recsys_username', 'fixture-admin');
    localStorage.setItem('recsys_roles', '["ADMIN"]');
  });
  await page.route('**/api/**', async (route) => {
    if (route.request().method() !== 'GET') return route.abort();
    await route.fulfill({ json: [] });
  });
}

const failures = [
  {
    name: '503目录码',
    status: 503,
    body: { code: 'SERVICE_UNAVAILABLE', message: privateDetail },
    code: 'SERVICE_UNAVAILABLE',
  },
  {
    name: '未知码',
    status: 503,
    body: { code: 'FUTURE_DEPENDENCY_ERROR', message: privateDetail },
    code: 'FUTURE_DEPENDENCY_ERROR',
  },
  { name: '空body', status: 500, body: '', code: 'INTERNAL_SERVER_ERROR' },
  { name: 'HTML', status: 502, body: `<html>${privateDetail}</html>`, code: 'BAD_GATEWAY' },
  { name: '网络中断', status: 0, body: '', code: 'NETWORK_ERROR' },
  {
    name: '403',
    status: 403,
    body: { code: 'FORBIDDEN', message: privateDetail },
    code: 'FORBIDDEN',
  },
];

for (const failure of failures) {
  test(`${failure.name}安全显示并恢复`, async ({ page }, testInfo) => {
    await isolatedAdmin(page);
    let recovered = false;
    await page.route('**/api/experiment', async (route) => {
      if (recovered) return route.fulfill({ json: snapshot });
      if (!failure.status) return route.abort('failed');
      await route.fulfill({
        status: failure.status,
        contentType: typeof failure.body === 'string' ? 'text/html' : 'application/json',
        body: typeof failure.body === 'string' ? failure.body : JSON.stringify(failure.body),
      });
    });
    await page.goto('/experiment');
    await expect(
      page
        .getByRole('alert')
        .filter({ hasText: `[${failure.code}]` })
        .first(),
    ).toBeVisible();
    await expect(page.locator('body')).not.toContainText(privateDetail);
    if (failure.status === 503 && failure.code === 'SERVICE_UNAVAILABLE') {
      await page.screenshot({
        path: testInfo.outputPath('safe-error-desktop.png'),
        fullPage: true,
      });
      await page.getByRole('button', { name: '收起菜单' }).click();
      await page.setViewportSize({ width: 390, height: 844 });
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollWidth))
        .toBeLessThanOrEqual(390);
      await page.screenshot({ path: testInfo.outputPath('safe-error-mobile.png'), fullPage: true });
    }
    recovered = true;
    await page.reload();
    await expect(page.getByText('全局实验开关')).toBeVisible();
    await expect(page.getByRole('spinbutton').first()).toHaveValue('50');
  });
}

test('401清会话并进入登录页，不展示服务端详情', async ({ page }) => {
  await isolatedAdmin(page);
  await page.route('**/api/experiment', (route) =>
    route.fulfill({ status: 401, json: { code: 'UNAUTHORIZED', message: privateDetail } }),
  );
  await page.goto('/experiment');
  await expect(page).toHaveURL(/\/login$/);
  await expect.poll(() => page.evaluate(() => localStorage.getItem('recsys_token'))).toBeNull();
  await expect(page.locator('body')).not.toContainText(privateDetail);
});

test('实验拒绝与坏ACK不显示成功，重新读取实际状态后可继续操作', async ({ page }, testInfo) => {
  await isolatedAdmin(page);
  let reads = 0;
  let writes = 0;
  await page.route('**/api/experiment', async (route) => {
    reads++;
    await route.fulfill({ json: snapshot });
  });
  await page.route('**/api/experiment/enabled?*', async (route) => {
    writes++;
    await route.fulfill({
      json:
        writes === 1
          ? { ok: false, reason: privateDetail }
          : writes === 2
            ? {}
            : { ok: true, value: false },
    });
  });
  await page.goto('/experiment');
  const control = page.getByRole('switch').first();
  for (let attempt = 1; attempt <= 2; attempt++) {
    await control.click();
    await expect(page.getByText('[EXPERIMENT_WRITE_UNCONFIRMED]', { exact: false })).toBeVisible();
    await expect.poll(() => reads).toBe(attempt + 1);
    await expect(page.locator('body')).not.toContainText(privateDetail);
    await expect(page.locator('.ant-message-success')).toHaveCount(0);
    if (attempt === 1) {
      // 通知进入动画完成后取证，避免截到opacity尚为0的瞬间。
      await page.waitForTimeout(300);
      await expect(page.locator('.ant-message-error')).toBeVisible();
      await page.screenshot({
        path: testInfo.outputPath('experiment-write-error.png'),
        fullPage: true,
      });
    }
    // 等通知自然退出，下一次拒绝必须产生新的可见消息。
    await expect(page.locator('.ant-message-error')).toHaveCount(0);
  }
  await page.screenshot({ path: testInfo.outputPath('experiment-recovered.png'), fullPage: true });
  await control.click();
  await expect(page.locator('.ant-message-success')).toContainText('全局实验 关闭');
  expect(writes).toBe(3);
});

test('渲染边界安全提示，保留重试并可在修复响应后刷新恢复', async ({ page }, testInfo) => {
  await isolatedAdmin(page);
  let healthy = false;
  await page.route('**/api/experiment', (route) =>
    route.fulfill({
      json: healthy ? snapshot : { ...snapshot, staticLayers: null, reason: privateDetail },
    }),
  );
  await page.goto('/experiment');
  await expect(page.getByRole('alert')).toContainText('[UI_RENDER_FAILED]');
  await expect(page.locator('body')).not.toContainText(privateDetail);
  await expect(page.locator('body')).not.toContainText('TypeError');
  await page.getByRole('button', { name: /重\s*试/ }).click();
  await expect(page.getByRole('alert')).toContainText('[UI_RENDER_FAILED]');
  await page.screenshot({ path: testInfo.outputPath('render-error-desktop.png'), fullPage: true });
  healthy = true;
  await page.reload();
  await expect(page.getByText('全局实验开关')).toBeVisible();
  await expect(page.getByText('[UI_RENDER_FAILED]', { exact: false })).toHaveCount(0);
});
