import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import type { ActionDelivery } from '../src/api/sandboxActions';
const fixture = JSON.parse(readFileSync(process.env.ACTIONS_FIXTURE_INFO!, 'utf8')) as {
  url: string;
  token: string;
  otherToken: string;
};
test.beforeEach(async ({ page }) => {
  await page.addInitScript((token) => {
    if (!localStorage.getItem('recsys_token')) {
      localStorage.setItem('recsys_token', token);
      localStorage.setItem('recsys_username', 'browser-admin');
      localStorage.setItem('recsys_roles', '["ADMIN"]');
    }
  }, fixture.token);
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      if (new URL(route.request().url()).pathname.startsWith('/api/sandbox/'))
        await route.continue();
      else await route.fulfill({ json: {} });
    },
  );
  await page.goto('/search-ads');
  await page.getByText('CPC / OCPC Sandbox', { exact: true }).click();
  await page.getByLabel('广告位', { exact: true }).fill('1');
});
test('先点击后可见、丢 ACK 查询、刷新不重送，真实账本一次', async ({ page, request }, info) => {
  const calls: { id: string; kind: string; body: unknown }[] = [];
  let dropped = false;
  await page.route('**/api/sandbox/click-receipts/*', async (route) => {
    if (route.request().method() === 'PUT') {
      const body = route.request().postDataJSON();
      calls.push({ id: route.request().url(), kind: body.kind, body });
      if (body.kind === 'CLICK' && !dropped) {
        dropped = true;
        await route.fetch();
        await route.abort('failed');
        return;
      }
    }
    await route.continue();
  });
  // 受控 hidden 用来冻结可见条件；实际交叉与一秒计时仍由浏览器完成。
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  const wait = page.waitForResponse(
    (r) => r.url().includes('/click-ad-deliveries/') && r.request().method() === 'PUT',
  );
  await page.getByRole('button', { name: '新交付', exact: true }).click();
  const d = (await (await wait).json()) as ActionDelivery;
  await page.getByRole('button', { name: '模拟点击', exact: true }).click();
  await expect(page.getByText('等待有效可见', { exact: true })).toBeVisible({ timeout: 15000 });
  expect(
    (await (await request.get(fixture.url + '/fixture/click/' + d.operationId)).json()).ledgerCount,
  ).toBe(0);
  expect(calls[0].body).toMatchObject({
    kind: 'CLICK',
    visibleDurationMs: 0,
    minVisibleRatioBps: 0,
    pageVisible: false,
  });
  await page.evaluate(() => {
    delete (document as unknown as { hidden?: boolean }).hidden;
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.getByTestId('action-creative-1').scrollIntoViewIfNeeded();
  await expect(page.getByText('资格实收 ¥0.123456', { exact: true })).toBeVisible({
    timeout: 15000,
  });
  expect(await (await request.get(fixture.url + '/fixture/click/' + d.operationId)).json()).toEqual(
    { ledgerCount: 1, outboxCount: 1, amount: '0.123456' },
  );
  expect(calls.filter((c) => c.kind === 'CLICK')).toHaveLength(1);
  expect(calls.filter((c) => c.kind === 'VISIBLE')).toHaveLength(1);
  await page.screenshot({
    path: info.outputPath('click-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByText('调试信息', { exact: false }).click();
  await expect(page.locator('.json-block')).toContainText('[REDACTED]');
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: /下载/ }).click();
  const text = readFileSync((await (await download).path())!, 'utf8');
  expect(text.includes(d.items[0].preparation!.qualificationToken!)).toBe(false);
  await page.reload();
  await page.getByText('CPC / OCPC Sandbox', { exact: true }).click();
  await expect(page.getByText('资格实收 ¥0.123456', { exact: true })).toBeVisible({
    timeout: 15000,
  });
  expect(calls).toHaveLength(2);
  await page.getByRole('button', { name: '收起菜单' }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({
    path: info.outputPath('click-narrow.png'),
    fullPage: true,
    animations: 'disabled',
  });
});
test('先可见后点击、零元有账本、身份切换拒绝恢复旧资格', async ({ page, request }) => {
  await page.getByLabel('广告位', { exact: true }).fill('2');
  const wait = page.waitForResponse(
    (r) => r.url().includes('/click-ad-deliveries/') && r.request().method() === 'PUT',
  );
  await page.getByRole('button', { name: '新交付', exact: true }).click();
  const d = (await (await wait).json()) as ActionDelivery;
  const row = page.getByRole('row').filter({ has: page.getByTestId('action-creative-2') });
  await page.getByTestId('action-creative-2').scrollIntoViewIfNeeded();
  await expect(row.getByText('等待点击', { exact: true })).toBeVisible();
  await row.getByRole('button', { name: '模拟点击', exact: true }).click();
  await expect(row.getByText('资格实收 ¥0.000000', { exact: true })).toBeVisible();
  const totals = await (await request.get(fixture.url + '/fixture/click/' + d.operationId)).json();
  expect(totals.ledgerCount).toBe(1);
  expect(totals.amount).toBe('0.000000');
  await page.evaluate((token) => {
    localStorage.setItem('recsys_token', token);
    window.dispatchEvent(new StorageEvent('storage', { key: 'recsys_token' }));
  }, fixture.otherToken);
  await page.reload();
  await page.getByText('CPC / OCPC Sandbox', { exact: true }).click();
  await expect(page.getByTestId('action-creative-2')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '新交付', exact: true })).toBeEnabled();
});

test('未知交付冻结原参数恢复；权限拒绝不回退旧接口', async ({ page }) => {
  const ids: string[] = [],
    bodies: unknown[] = [];
  let failed = false;
  let legacy = 0;
  page.on('request', (req) => {
    if (/\/api\/(ad\/click|ad\/conversion|ad\/outcome)/.test(req.url())) legacy++;
  });
  await page.route('**/api/sandbox/click-ad-deliveries/*', async (route) => {
    if (route.request().method() === 'PUT') {
      ids.push(route.request().url());
      bodies.push(route.request().postDataJSON());
      if (!failed) {
        failed = true;
        await route.fetch();
        await route.abort('failed');
        return;
      }
    }
    await route.continue();
  });
  await page.getByRole('button', { name: '新交付', exact: true }).click();
  await expect(page.getByText('交付结果待确认', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '新交付', exact: true })).toBeDisabled();
  await page.getByLabel('模拟用户 ID', { exact: true }).fill('9223372036854775807');
  await page.getByRole('button', { name: '恢复原操作', exact: true }).click();
  await expect(page.getByTestId('action-creative-1')).toBeVisible();
  expect(ids).toHaveLength(1);
  expect(bodies).toHaveLength(1);
  await page.getByRole('button', { name: '请求历史', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '请求历史' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: '请求历史' })).toBeHidden();
  await page.route('**/api/sandbox/click-receipts/*', (route) =>
    route.fulfill({ status: 403, json: { code: 'CLICK_RECEIPT_FORBIDDEN' } }),
  );
  await page.getByRole('button', { name: '模拟点击', exact: true }).click();
  await expect(page.getByText('操作已阻止', { exact: true })).toBeVisible();
  expect(legacy).toBe(0);
});
