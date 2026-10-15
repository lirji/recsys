import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { nativeBrowser } from './nativeBrowser';
import type { NaturalDeliveryView } from '../src/api/naturalExposure';
const fixture = JSON.parse(
  readFileSync(
    process.env.NATURAL_FIXTURE_INFO ?? '../recsys-rec-engine/target/natural-browser-fixture.json',
    'utf8',
  ),
) as { url: string; token: string; otherToken: string };
const deliver = async (page: Page) => {
  const wait = page.waitForResponse(
    (r) => r.url().includes('/api/sandbox/natural-deliveries/') && r.request().method() === 'PUT',
  );
  await page.getByRole('button', { name: '新交付', exact: true }).click();
  const response = await wait;
  expect(response.status()).toBe(200);
  return (await response.json()) as NaturalDeliveryView;
};
test.beforeEach(async ({ page }) => {
  await page.addInitScript((token) => {
    if (!localStorage.getItem('recsys_token')) {
      localStorage.setItem('recsys_token', token);
      localStorage.setItem('recsys_username', 'browser-admin');
      localStorage.setItem('recsys_roles', '["ADMIN"]');
    }
  }, fixture.token);
  // 无关壳层依赖隔离；自然交付和回执必须走真实 owner HTTP/PG。
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      if (new URL(route.request().url()).pathname.startsWith('/api/sandbox/'))
        await route.continue();
      else await route.fulfill({ json: {} });
    },
  );
});
test('自然推荐真实可见、丢 ACK、刷新同 ID、大 ID 与历史对比脱敏', async ({
  page,
  request,
}, info) => {
  const putIds: string[] = [],
    operations: string[] = [],
    oldCalls: string[] = [];
  page.on('request', (r) => {
    if (r.method() === 'PUT' && r.url().includes('/natural-deliveries/')) operations.push(r.url());
    if (
      /^\/api\/(recommend|search|behavior|search-ads|ad-click|ad-conversion)(?:\/|$)/.test(
        new URL(r.url()).pathname,
      )
    )
      oldCalls.push(r.url());
  });
  let dropped = false;
  await page.route('**/api/sandbox/natural-impressions/*', async (route) => {
    if (route.request().method() === 'PUT') {
      putIds.push(route.request().url());
      if (!dropped) {
        dropped = true;
        const r = await route.fetch();
        expect(r.status()).toBe(200);
        await route.abort('failed');
        return;
      }
    }
    await route.continue();
  });
  await page.goto('/recommend');
  await page.getByLabel('模拟用户 ID', { exact: true }).fill('9223372036854775807');
  await page.getByLabel('结果数', { exact: true }).fill('1');
  const original = await deliver(page);
  expect(original.kind).toBe('RECOMMEND');
  expect(original.query).toBeNull();
  expect(original.items[0].itemId).toBe('9007199254740993');
  await page.getByLabel('模拟用户 ID', { exact: true }).fill('999');
  await page.getByTestId('natural-content-1').scrollIntoViewIfNeeded();
  await expect(page.getByText('曝光已接受', { exact: true })).toBeVisible({ timeout: 15000 });
  expect(putIds).toHaveLength(1);
  const facts = async () =>
    (await request.get(fixture.url + '/fixture/operation/' + original.operationId)).json();
  expect(await facts()).toEqual({
    factCount: 1,
    outboxCount: 1,
    users: ['9223372036854775807'],
    items: ['9007199254740993'],
  });
  await page.screenshot({ path: info.outputPath('natural-recommend-desktop.png'), fullPage: true });
  await page.getByText('调试信息', { exact: false }).click();
  await expect(page.locator('.json-block')).toContainText('[REDACTED]');
  await expect(page.locator('.json-block')).not.toContainText(original.items[0].qualificationToken);
  const downloadWait = page.waitForEvent('download');
  await page.getByRole('button', { name: /下载/ }).click();
  const exported = readFileSync((await (await downloadWait).path())!, 'utf8');
  expect(exported).not.toContain(original.items[0].qualificationToken);
  expect(exported).toContain('[REDACTED]');
  await page.reload();
  await expect(page.getByText('曝光已接受', { exact: true })).toBeVisible();
  expect(operations).toHaveLength(1);
  expect(putIds).toHaveLength(1);
  const second = await deliver(page);
  expect(second.operationId).not.toBe(original.operationId);
  expect(second.items[0].qualificationId).not.toBe(original.items[0].qualificationId);
  await page.getByRole('button', { name: '请求历史', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '请求历史' })).toBeVisible();
  await page.screenshot({
    path: info.outputPath('natural-history.png'),
    fullPage: true,
    animations: 'disabled',
  });
  const comparisons = page.getByRole('button', { name: /对\s*比/, exact: true });
  await comparisons.nth(0).click();
  await comparisons.nth(0).click();
  await page.getByRole('button', { name: /对比选中的两条/ }).click();
  await expect(page.getByRole('dialog', { name: '自然交付对比（只读）' })).toBeVisible();
  await page.waitForTimeout(1200);
  expect(
    (
      await request
        .get(fixture.url + '/fixture/operation/' + second.operationId)
        .then((r) => r.json())
    ).factCount,
  ).toBe(0);
  await page.screenshot({
    path: info.outputPath('natural-compare.png'),
    fullPage: true,
    animations: 'disabled',
  });
  expect(operations).toHaveLength(2);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: '自然交付对比（只读）' })).not.toBeVisible();
  const rerunWait = page.waitForResponse(
    (r) => r.url().includes('/natural-deliveries/') && r.request().method() === 'PUT',
  );
  await page
    .getByRole('button', { name: /重\s*跑/ })
    .first()
    .click();
  const rerun = (await (await rerunWait).json()) as NaturalDeliveryView;
  expect(rerun.operationId).not.toBe(second.operationId);
  expect(rerun.items[0].qualificationId).not.toBe(second.items[0].qualificationId);
  expect(rerun.userId).toBe(original.userId);
  await page.getByTestId('natural-content-1').scrollIntoViewIfNeeded();
  await expect(page.getByText('曝光已接受', { exact: true })).toBeVisible();
  expect(operations).toHaveLength(3);
  expect(oldCalls).toHaveLength(0);
});
test('自然搜索真实空结果、窄屏与推荐搜索意图隔离', async ({ page, request }, info) => {
  await page.goto('/search');
  await page.getByLabel('结果数', { exact: true }).fill('1');
  const result = await deliver(page);
  expect(result.kind).toBe('SEARCH');
  expect(result.query).toBe('action');
  await page.getByTestId('natural-content-1').scrollIntoViewIfNeeded();
  await expect(page.getByText('曝光已接受', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '收起菜单' }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({
    path: info.outputPath('natural-search-narrow.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByLabel('搜索词', { exact: true }).fill('no-matching-seed');
  const empty = await deliver(page);
  expect(empty.items).toHaveLength(0);
  await expect(page.getByText('本次没有自然结果', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '新交付', exact: true })).toBeEnabled();
  expect(
    (
      await request
        .get(fixture.url + '/fixture/operation/' + empty.operationId)
        .then((r) => r.json())
    ).factCount,
  ).toBe(0);
  await page.screenshot({ path: info.outputPath('natural-empty-narrow.png'), fullPage: true });
  await page.goto('/recommend');
  await expect(page.getByRole('button', { name: '新交付', exact: true })).toBeEnabled();
  await expect(page.getByTestId('natural-item-1')).toHaveCount(0);
  await page.goto('/search');
  await expect(page.getByText('本次没有自然结果', { exact: true })).toBeVisible();
});
test('原生 Chromium 离屏与后台不生成自然事实，返回重新连续计时', async ({ request }) => {
  const browser = await nativeBrowser();
  try {
    await browser.page('Emulation.setDeviceMetricsOverride', {
      width: 1280,
      height: 450,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await browser.page('Page.addScriptToEvaluateOnNewDocument', {
      source: `localStorage.setItem('recsys_token', ${JSON.stringify(fixture.token)}); localStorage.setItem('recsys_username','browser-admin'); localStorage.setItem('recsys_roles','["ADMIN"]');`,
    });
    await browser.page('Page.navigate', { url: 'http://127.0.0.1:4184/recommend' });
    await expect
      .poll(() =>
        browser.evaluate<boolean>(
          `Array.from(document.querySelectorAll('button')).some(b => b.textContent === '新交付' && !b.disabled)`,
        ),
      )
      .toBe(true);
    await browser.evaluate(
      `Array.from(document.querySelectorAll('button')).find(b => b.textContent === '新交付').click()`,
    );
    await expect
      .poll(() =>
        browser.evaluate<number>(
          `document.querySelectorAll('[data-testid^="natural-content-"]').length`,
        ),
      )
      .toBe(3);
    const id = await browser.evaluate<string>(
      `JSON.parse(sessionStorage.getItem('recsys.sandbox.natural.recommend.v1')).operationId`,
    );
    const facts = async () =>
      (await request.get(fixture.url + '/fixture/operation/' + id).then((r) => r.json())).factCount;
    await new Promise((r) => setTimeout(r, 1200));
    expect(await facts()).toBe(0);
    await browser.evaluate(
      `document.querySelector('[data-testid="natural-content-1"]').scrollIntoView({block:'center'})`,
    );
    await expect
      .poll(() => browser.evaluate<boolean>(`document.body.innerText.includes('可见计时中')`), {
        intervals: [25],
      })
      .toBe(true);
    await browser.background();
    await expect.poll(() => browser.evaluate<string>('document.visibilityState')).toBe('hidden');
    await new Promise((r) => setTimeout(r, 1300));
    expect(await facts()).toBe(0);
    await browser.send('Target.activateTarget', { targetId: browser.targetId });
    await expect.poll(() => browser.evaluate<string>('document.visibilityState')).toBe('visible');
    await new Promise((r) => setTimeout(r, 500));
    expect(await facts()).toBe(0);
    await expect.poll(facts).toBeGreaterThan(0);
  } finally {
    await browser.close();
  }
});
test('身份切换不恢复旧意图，服务器拒绝跨主体查询', async ({ page, request }) => {
  await page.goto('/recommend');
  await page.getByLabel('结果数', { exact: true }).fill('1');
  const delivery = await deliver(page);
  await page.getByTestId('natural-content-1').scrollIntoViewIfNeeded();
  await expect(page.getByText('曝光已接受', { exact: true })).toBeVisible();
  expect(
    (
      await request.get(fixture.url + '/api/sandbox/natural-deliveries/' + delivery.operationId, {
        headers: { Authorization: 'Bearer ' + fixture.otherToken },
      })
    ).status(),
  ).toBe(403);
  await page.evaluate((token) => {
    localStorage.setItem('recsys_token', token);
    localStorage.setItem('recsys_username', 'other-admin');
    window.dispatchEvent(new StorageEvent('storage', { key: 'recsys_token' }));
  }, fixture.otherToken);
  await page.reload();
  await expect(page.getByRole('button', { name: '新交付', exact: true })).toBeEnabled();
  await expect(page.getByTestId('natural-item-1')).toHaveCount(0);
  expect(
    await page.evaluate(() => sessionStorage.getItem('recsys.sandbox.natural.recommend.v1')),
  ).toBeNull();
});
test('受控 503/202 原 ID 原参数恢复，未知政策不采集', async ({ page }, info) => {
  const bodies: unknown[] = [],
    ids: string[] = [];
  let attempt = 0;
  await page.route('**/api/sandbox/natural-deliveries/*', async (route) => {
    if (route.request().method() !== 'PUT') {
      await route.continue();
      return;
    }
    attempt++;
    bodies.push(route.request().postDataJSON());
    ids.push(route.request().url());
    if (attempt === 1 || attempt === 5) {
      await route.fulfill({ status: 503, json: { message: '测试未知结果' } });
      return;
    }
    const actual = await route.fetch();
    const body = (await actual.json()) as NaturalDeliveryView;
    if (attempt === 2)
      await route.fulfill({ status: 202, json: { ...body, state: 'PREPARING', items: [] } });
    else if (attempt === 3)
      await route.fulfill({ json: { ...body, policy: { ...body.policy, minDurationMs: 500 } } });
    else await route.fulfill({ response: actual });
  });
  await page.goto('/recommend');
  await page.getByRole('button', { name: '新交付', exact: true }).click();
  await expect(page.getByText('交付结果尚未确认', { exact: true })).toBeVisible();
  await page.getByLabel('模拟用户 ID', { exact: true }).fill('888');
  await page.getByRole('button', { name: '恢复原操作', exact: true }).click();
  await expect(page.getByText('操作准备中，请恢复原操作', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '新交付', exact: true })).toBeDisabled();
  await page.screenshot({ path: info.outputPath('natural-preparing.png'), fullPage: true });
  await page.getByRole('button', { name: '恢复原操作', exact: true }).click();
  await expect(
    page.getByText('自然交付响应与原请求或可见政策不匹配', { exact: true }),
  ).toBeVisible();
  await expect(page.getByTestId('natural-item-1')).toHaveCount(0);
  await page.screenshot({ path: info.outputPath('natural-policy-error.png'), fullPage: true });
  await page.getByRole('button', { name: '恢复原操作', exact: true }).click();
  await expect(page.getByTestId('natural-item-1')).toBeVisible();
  expect(new Set(ids).size).toBe(1);
  expect(bodies.every((b) => JSON.stringify(b) === JSON.stringify(bodies[0]))).toBe(true);
  await page.getByRole('button', { name: '新交付', exact: true }).click();
  await expect(page.getByText('交付结果尚未确认', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '请求历史', exact: true }).click();
  await expect(page.getByRole('button', { name: /重\s*跑/ })).toBeDisabled();
});
