import { expect, test } from '@playwright/test';
import { nativeBrowser } from './nativeBrowser';
import { readFileSync } from 'node:fs';
import type { SandboxDeliveryView } from '../src/api/exposure';

const fixture = JSON.parse(
  readFileSync(
    process.env.SANDBOX_FIXTURE_INFO ?? '../recsys-rec-engine/target/browser-fixture.json',
    'utf8',
  ),
) as { url: string; token: string; otherToken: string };
test.beforeEach(async ({ page }) => {
  await page.addInitScript((token) => {
    if (!localStorage.getItem('recsys_token')) {
      localStorage.setItem('recsys_token', token);
      localStorage.setItem('recsys_username', 'browser-admin');
      localStorage.setItem('recsys_roles', '["ADMIN"]');
    }
  }, fixture.token);
  // 隔离无关导航/报表依赖；sandbox API 必须经过真实 HTTP、PG 和 Kafka。
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      if (new URL(route.request().url()).pathname.startsWith('/api/sandbox/'))
        await route.continue();
      else await route.fulfill({ json: {} });
    },
  );
});
test('真实可见回执、丢 ACK 恢复、刷新和历史脱敏只产生一笔账', async ({
  page,
  request,
}, testInfo) => {
  let oldCalls = 0;
  const receiptIds: string[] = [];
  page.on('request', (req) => {
    if (/\/api\/(search-ads|ad-click|ad-conversion)/.test(req.url())) oldCalls += 1;
  });
  let dropped = false;
  await page.route('**/api/sandbox/ad-impressions/*', async (route) => {
    if (route.request().method() === 'PUT') {
      receiptIds.push(route.request().url().split('/').at(-1)!);
      if (!dropped) {
        dropped = true;
        await route.fetch(); // 真实服务已完成，故意丢弃返回包验证原 ID 查询。
        await route.abort('failed');
        return;
      }
    }
    await route.continue();
  });
  await page.goto('/search-ads');
  await expect(page.getByText('搜索广告 · Sandbox', { exact: true })).toBeVisible();
  await page.getByLabel('广告位', { exact: true }).fill('1');
  const responseWait = page.waitForResponse(
    (r) => r.url().includes('/api/sandbox/search-ad-deliveries/') && r.request().method() === 'PUT',
  );
  await page.getByRole('button', { name: '新交付', exact: true }).click();
  const delivery = (await (await responseWait).json()) as SandboxDeliveryView;
  await page.getByLabel('模拟用户 ID', { exact: true }).fill('999');
  await page.getByTestId('creative-1').scrollIntoViewIfNeeded();
  await expect(page.getByText('已确认消费 ¥0.010001', { exact: true })).toBeVisible({
    timeout: 15000,
  });
  expect(receiptIds).toHaveLength(1);
  await expect
    .poll(
      async () =>
        (
          await (
            await request.get(fixture.url + '/fixture/operation/' + delivery.operationId)
          ).json()
        ).projectedCount,
    )
    .toBe(1);
  const totals = await (
    await request.get(fixture.url + '/fixture/operation/' + delivery.operationId)
  ).json();
  expect(totals).toEqual({ ledgerCount: 1, projectedCount: 1, amount: '0.010001' });
  await page.screenshot({
    path: testInfo.outputPath('sandbox-confirmed-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByText('调试信息', { exact: false }).click();
  await expect(page.locator('.json-block')).toContainText('[REDACTED]');
  await expect(page.locator('.json-block')).not.toContainText(
    delivery.items[0].preparation!.qualificationToken!,
  );
  const downloadWait = page.waitForEvent('download');
  await page.getByRole('button', { name: /下载/ }).click();
  const downloaded = await downloadWait;
  const exported = readFileSync((await downloaded.path())!, 'utf8');
  expect(exported).toContain('[REDACTED]');
  expect(exported).not.toContain(delivery.items[0].preparation!.qualificationToken!);
  await page.getByRole('button', { name: '请求历史', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '请求历史' })).toBeVisible();
  await expect(page.getByRole('button', { name: /重\s*跑/ })).toBeInViewport();
  await page.waitForTimeout(350); // 等抽屉有限 CSS 动画结束后留存视觉证据。
  await page.screenshot({
    path: testInfo.outputPath('sandbox-history.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.keyboard.press('Escape');
  await page.reload();
  await expect(page.getByText('已确认消费 ¥0.010001', { exact: true })).toBeVisible();
  expect(receiptIds).toHaveLength(1);
  expect(oldCalls).toBe(0);
  await page.getByRole('button', { name: '收起菜单' }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() =>
      page.locator('.shell-sider').evaluate((element) => element.getBoundingClientRect().width),
    )
    .toBeLessThanOrEqual(1); // 折叠后保留 1px 分隔线。
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({
    path: testInfo.outputPath('sandbox-narrow.png'),
    fullPage: true,
    animations: 'disabled',
  });
});
test('离屏和受控 hidden 重置测量，身份切换不恢复旧操作', async ({ page, request }) => {
  const receiptIds: string[] = [];
  page.on('request', (req) => {
    if (req.method() === 'PUT' && req.url().includes('/api/sandbox/ad-impressions/'))
      receiptIds.push(req.url());
  });
  await page.goto('/search-ads');
  await page.getByLabel('广告位', { exact: true }).fill('1');
  // 页面可见性事件的受控注入单独标识；真实 viewport 交叉仍由浏览器测量。
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  const responseWait = page.waitForResponse(
    (r) => r.url().includes('/api/sandbox/search-ad-deliveries/') && r.request().method() === 'PUT',
  );
  await page.getByRole('button', { name: '新交付', exact: true }).click();
  const delivery = (await (await responseWait).json()) as SandboxDeliveryView;
  await page.getByTestId('creative-1').scrollIntoViewIfNeeded();
  await page.waitForTimeout(1200);
  expect(receiptIds).toHaveLength(0);
  expect(
    (await (await request.get(fixture.url + '/fixture/operation/' + delivery.operationId)).json())
      .ledgerCount,
  ).toBe(0);
  await page.evaluate(() => {
    delete (document as unknown as { hidden?: boolean }).hidden;
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect(page.getByText('已确认消费 ¥0.010001', { exact: true })).toBeVisible();
  await expect
    .poll(
      async () =>
        (
          await (
            await request.get(fixture.url + '/fixture/operation/' + delivery.operationId)
          ).json()
        ).projectedCount,
    )
    .toBe(1);
  await page.evaluate((token) => {
    localStorage.setItem('recsys_token', token);
    localStorage.setItem('recsys_username', 'other-admin');
    window.dispatchEvent(new StorageEvent('storage', { key: 'recsys_token' }));
  }, fixture.otherToken);
  await page.reload();
  await expect(page.getByRole('button', { name: '新交付', exact: true })).toBeEnabled();
  await expect(page.getByTestId('creative-1')).toHaveCount(0);
  expect(receiptIds).toHaveLength(1);
});

test('原生 Chromium 标签页 hidden 与实际离屏不计费，恢复重新连续计时', async ({ request }) => {
  const browser = await nativeBrowser();
  try {
    await browser.page('Page.addScriptToEvaluateOnNewDocument', {
      source: `localStorage.setItem('recsys_token', ${JSON.stringify(fixture.token)}); localStorage.setItem('recsys_username','browser-admin'); localStorage.setItem('recsys_roles','["ADMIN"]');`,
    });
    await browser.page('Page.navigate', { url: 'http://127.0.0.1:4183/search-ads' });
    await expect
      .poll(() =>
        browser.evaluate<boolean>(
          `Array.from(document.querySelectorAll('button')).some(b => b.textContent === '新交付' && !b.disabled)`,
        ),
      )
      .toBe(true);
    // 先保持实际创意离屏，再由真实滚动触发测量。
    await browser.evaluate(
      `Array.from(document.querySelectorAll('button')).find(b => b.textContent === '新交付').click()`,
    );
    await expect
      .poll(() =>
        browser.evaluate<number>(`document.querySelectorAll('[data-testid^="creative-"]').length`),
      )
      .toBe(3);
    const operationId = await browser.evaluate<string>(
      `JSON.parse(sessionStorage.getItem('recsys.sandbox.delivery.v1')).operationId`,
    );
    const totals = async () =>
      await (await request.get(fixture.url + '/fixture/operation/' + operationId)).json();
    await new Promise((resolve) => setTimeout(resolve, 1200));
    expect((await totals()).ledgerCount).toBe(0);
    await browser.evaluate(
      `document.querySelector('[data-testid="creative-1"]').scrollIntoView({block:'center'})`,
    );
    await expect
      .poll(() => browser.evaluate<boolean>(`document.body.innerText.includes('可见计时中')`), {
        intervals: [25],
      })
      .toBe(true);
    // 创建页签后显式激活，避免CI中创建完成但原页签仍处于前台。
    const hiddenTab = await browser.send('Target.createTarget', { url: 'about:blank' });
    await browser.send('Target.activateTarget', { targetId: hiddenTab.targetId });
    await expect.poll(() => browser.evaluate<string>('document.visibilityState')).toBe('hidden');
    await new Promise((resolve) => setTimeout(resolve, 1300));
    expect((await totals()).ledgerCount).toBe(0);
    await browser.send('Target.activateTarget', { targetId: browser.targetId });
    await expect.poll(() => browser.evaluate<string>('document.visibilityState')).toBe('visible');
    await new Promise((resolve) => setTimeout(resolve, 500));
    expect((await totals()).ledgerCount).toBe(0);
    await expect.poll(async () => (await totals()).ledgerCount).toBeGreaterThan(0);
    await expect
      .poll(async () => {
        const t = await totals();
        return t.projectedCount === t.ledgerCount;
      })
      .toBe(true);
  } finally {
    await browser.close();
  }
});

test('受控契约状态：503、202 按原参数恢复，过期及空结果不伪造消费', async ({ page }, testInfo) => {
  const bodies: unknown[] = [];
  const ids: string[] = [];
  let attempts = 0;
  await page.route('**/api/sandbox/search-ad-deliveries/*', async (route) => {
    if (route.request().method() !== 'PUT') {
      await route.continue();
      return;
    }
    attempts += 1;
    bodies.push(route.request().postDataJSON());
    ids.push(route.request().url());
    if (attempts === 1) {
      await route.fulfill({ status: 503, json: { message: '测试未知结果' } });
      return;
    }
    const actual = await route.fetch();
    const body = (await actual.json()) as SandboxDeliveryView;
    if (attempts === 2) {
      await route.fulfill({ status: 202, json: { ...body, state: 'PREPARING', items: [] } });
      return;
    }
    if (attempts === 3) {
      await route.fulfill({
        json: {
          ...body,
          items: body.items.map((item) => ({
            ...item,
            preparation: {
              ...item.preparation,
              expiresAt: new Date(Date.now() - 1000).toISOString(),
            },
          })),
        },
      });
      return;
    }
    await route.fulfill({ json: { ...body, items: [] } });
  });
  await page.goto('/search-ads');
  await page.getByRole('button', { name: '新交付', exact: true }).click();
  await expect(page.getByText('交付结果尚未确认')).toBeVisible();
  await expect(page.getByRole('button', { name: '新交付', exact: true })).toBeDisabled();
  await page.screenshot({
    path: testInfo.outputPath('sandbox-unknown.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByLabel('模拟用户 ID', { exact: true }).fill('999');
  await page.getByRole('button', { name: '恢复原操作', exact: true }).click();
  await expect(page.getByText('操作准备中，请恢复原操作')).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath('sandbox-preparing.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('button', { name: '恢复原操作', exact: true }).click();
  await expect(page.getByText('未提交，资格已过期').first()).toBeVisible();
  expect(new Set(ids).size).toBe(1);
  expect(bodies[1]).toEqual(bodies[0]);
  expect(bodies[2]).toEqual(bodies[0]);
  await page.screenshot({
    path: testInfo.outputPath('sandbox-expired.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('button', { name: '新交付', exact: true }).click();
  await expect(page.getByText('本次没有可交付广告')).toBeVisible();
  expect(new Set(ids).size).toBe(2);
  await page.screenshot({
    path: testInfo.outputPath('sandbox-empty.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByRole('button', { name: '请求历史', exact: true }).click();
  const compareButtons = page
    .getByRole('dialog', { name: '请求历史' })
    .getByRole('button', { name: /^对\s*比$/ });
  await compareButtons.nth(0).click();
  await compareButtons.nth(0).click();
  await page.getByRole('button', { name: '对比选中的两条 (2/2)' }).click();
  await expect(page.getByRole('dialog', { name: '交付对比（只读）' })).toBeVisible();
  await page.waitForTimeout(350);
  await page.screenshot({
    path: testInfo.outputPath('sandbox-compare.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: '交付对比（只读）' })).toBeHidden();
  expect(attempts).toBe(4);
});
