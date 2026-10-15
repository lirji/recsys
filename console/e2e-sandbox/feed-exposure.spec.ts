import { expect, test, type Page, type APIRequestContext } from '@playwright/test';
import { readFileSync } from 'node:fs';
import type { FeedDeliveryView } from '../src/api/feedExposure';
import { nativeBrowser } from './nativeBrowser';
const fixture = JSON.parse(
  readFileSync(
    process.env.FEED_FIXTURE_INFO ?? '../recsys-rec-engine/target/feed-browser-fixture.json',
    'utf8',
  ),
) as { url: string; token: string; otherToken: string };
const facts = async (request: APIRequestContext, id: string) =>
  (await request.get(fixture.url + '/fixture/feed/' + id)).json();
async function deliver(page: Page, action = '新交付', status = 200) {
  const response = page.waitForResponse(
    (r) => r.url().includes('/api/sandbox/feed-deliveries/') && r.request().method() === 'PUT',
  );
  await page.getByRole('button', { name: action, exact: true }).click();
  const result = await response;
  expect(result.status()).toBe(status);
  return (await result.json()) as FeedDeliveryView;
}
async function open(page: Page, user: string) {
  await page.goto('/feed');
  await page.getByLabel('模拟用户 ID', { exact: true }).fill(user);
  await page.getByLabel('搜索词（可选）', { exact: true }).fill('action');
  await page.getByLabel('结果数', { exact: true }).fill('5');
}
async function viewAll(page: Page, value: FeedDeliveryView) {
  for (const item of value.items) {
    if (item.kind === 'NATURAL') {
      await page.getByTestId('natural-content-' + item.slot).scrollIntoViewIfNeeded();
      await expect(
        page.getByTestId('natural-item-' + item.slot).getByText('曝光已接受', { exact: true }),
      ).toBeVisible();
    } else if (item.ad.preparation?.status === 'QUALIFIED') {
      await page.getByTestId('creative-' + item.slot).scrollIntoViewIfNeeded();
      await expect(
        page.getByTestId('feed-ad-' + item.slot).getByText('已确认消费 ¥0.123456', { exact: true }),
      ).toBeVisible({ timeout: 15000 });
    }
  }
}
test.beforeEach(async ({ page, request }) => {
  await request.post(fixture.url + '/fixture/feed-mode/NORMAL');
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
});
test('最终混排双回执、丢ACK与刷新恢复、大ID和可信频控', async ({ page, request }, info) => {
  const writes: string[] = [],
    legacy: string[] = [];
  let dropped = false;
  page.on('request', (r) => {
    if (r.method() === 'PUT' && r.url().includes('/feed-deliveries/')) writes.push(r.url());
    if (/^\/api\/(feed|recommend|search-ads|behavior)(?:\/|$)/.test(new URL(r.url()).pathname))
      legacy.push(r.url());
  });
  await page.route('**/api/sandbox/ad-impressions/*', async (route) => {
    if (route.request().method() === 'PUT' && !dropped) {
      dropped = true;
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      await route.abort('failed');
    } else await route.continue();
  });
  await open(page, '9223372036854775807');
  const result = await deliver(page);
  expect(result.items.map((e) => e.kind)).toEqual(['NATURAL', 'AD', 'NATURAL', 'AD', 'NATURAL']);
  expect(result.items[0].natural?.itemId).toBe('9007199254740993');
  await page.getByLabel('模拟用户 ID', { exact: true }).fill('999');
  await viewAll(page, result);
  expect(await facts(request, result.operationId)).toMatchObject({
    qualified: 2,
    ledgers: 2,
    charged: '0.246912',
    adSlots: [2, 4],
    naturalSlots: [1, 3, 5],
  });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: info.outputPath('feed-mixed-desktop.png'), fullPage: true });
  await page.reload();
  await expect(page.getByTestId('feed-ad-2')).toBeVisible();
  expect(writes).toHaveLength(1);
  expect(await facts(request, result.operationId)).toMatchObject({
    ledgers: 2,
    charged: '0.246912',
  });
  // 同原模拟用户的新操作：已接受广告达到每广告上限，频控剔除后不再预占。
  const capped = await deliver(page);
  expect(capped.items.every((e) => e.kind === 'NATURAL')).toBe(true);
  expect(await facts(request, capped.operationId)).toMatchObject({
    qualified: 0,
    ledgers: 0,
    children: [],
  });
  expect(legacy).toHaveLength(0);
});
test('真实prepare提交丢响应后自然可浏览，刷新原ID恢复位置和唯一预占', async ({
  page,
  request,
}, info) => {
  await request.post(fixture.url + '/fixture/feed-mode/PREPARE_ACK_LOST');
  await open(page, '10002');
  const first = await deliver(page, '新交付', 202);
  expect(first.items[1].ad?.preparation).toBeNull();
  await expect(page.getByTestId('feed-ad-2').getByText('广告准备结果未知')).toBeVisible();
  await expect(page.getByTestId('creative-2')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '新交付', exact: true })).toBeDisabled();
  await page.getByTestId('natural-content-1').scrollIntoViewIfNeeded();
  await expect(
    page.getByTestId('natural-item-1').getByText('曝光已接受', { exact: true }),
  ).toBeVisible();
  expect(await facts(request, first.operationId)).toMatchObject({
    qualified: 1,
    ledgers: 0,
    naturalSlots: [1],
  });
  await page.screenshot({ path: info.outputPath('feed-preparing.png'), fullPage: true });
  await page.reload();
  await expect(page.getByText('部分广告准备尚未确认')).toBeVisible();
  await page.getByLabel('模拟用户 ID', { exact: true }).fill('20002');
  const recovered = await deliver(page, '恢复原操作');
  expect(recovered.operationId).toBe(first.operationId);
  expect(recovered.userId).toBe('10002');
  expect(recovered.items.filter((e) => e.kind === 'NATURAL')).toEqual(
    first.items.filter((e) => e.kind === 'NATURAL'),
  );
  expect(recovered.items[1].ad?.childId).toBe(first.items[1].ad?.childId);
  await viewAll(page, recovered);
  expect(await facts(request, first.operationId)).toMatchObject({
    qualified: 2,
    ledgers: 2,
    adSlots: [2, 4],
    naturalSlots: [1, 3, 5],
  });
});
test('候选真实故障自然降级与预算拒绝占位，窄屏和空结果', async ({ page, request }, info) => {
  await request.post(fixture.url + '/fixture/feed-mode/CANDIDATE_DOWN');
  await open(page, '10003');
  const degraded = await deliver(page);
  expect(degraded.adDisposition).toBe('CANDIDATES_UNAVAILABLE');
  await expect(page.getByText('本次广告暂不可用，已交付自然结果')).toBeVisible();
  expect(await facts(request, degraded.operationId)).toMatchObject({ qualified: 0, children: [] });
  await request.post(fixture.url + '/fixture/feed-mode/NORMAL');
  expect(await deliver(page, '恢复原操作')).toEqual(degraded);
  await request.post(fixture.url + '/fixture/feed-mode/BUDGET_DENIED');
  const denied = await deliver(page);
  expect(denied.items[1].ad?.preparation?.status).toBe('INSUFFICIENT_BUDGET');
  await expect(page.getByTestId('feed-ad-2').getByText('预算不足，本位置未投放')).toBeVisible();
  await expect(page.getByTestId('creative-2')).toHaveCount(0);
  await viewAll(page, denied);
  expect(await facts(request, denied.operationId)).toMatchObject({
    qualified: 0,
    ledgers: 0,
    naturalSlots: [1, 3, 5],
  });
  await page.getByRole('button', { name: '收起菜单' }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: info.outputPath('feed-rejected-narrow.png'),
    fullPage: true,
    animations: 'disabled',
  });
  await page.getByLabel('搜索词（可选）', { exact: true }).fill('no-matching-seed');
  const empty = await deliver(page);
  expect(empty.items).toHaveLength(0);
  await expect(page.getByText('本次没有可交付内容')).toBeVisible();
  await page.screenshot({ path: info.outputPath('feed-empty-narrow.png'), fullPage: true });
});
test('历史对比遮盖不产生曝光，原资格脱敏和退出身份隔离', async ({ page, request }, info) => {
  await open(page, '10004');
  const first = await deliver(page);
  const second = await deliver(page);
  await page.getByRole('button', { name: '请求历史', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '请求历史' })).toBeVisible();
  await expect
    .poll(() =>
      page
        .getByRole('dialog', { name: '请求历史' })
        .evaluate((element) => Math.round(element.getBoundingClientRect().right)),
    )
    .toBe(1280);
  await page.screenshot({
    path: info.outputPath('feed-history.png'),
    fullPage: false,
    animations: 'disabled',
  });
  const compare = page.getByRole('button', { name: /对\s*比/, exact: true });
  await compare.nth(0).click();
  await compare.nth(0).click();
  await page.getByRole('button', { name: /对比选中的两条/ }).click();
  await expect(page.getByRole('dialog', { name: '混排交付对比（只读）' })).toBeVisible();
  await page.waitForTimeout(1200);
  expect(await facts(request, second.operationId)).toMatchObject({ ledgers: 0, naturalSlots: [] });
  await page.screenshot({
    path: info.outputPath('feed-compare.png'),
    fullPage: false,
    animations: 'disabled',
  });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: '混排交付对比（只读）' })).not.toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: '请求历史' })).not.toBeVisible();
  await page.getByText('调试信息', { exact: false }).click();
  await expect(page.locator('.json-block')).toContainText('[REDACTED]');
  await expect(page.locator('.json-block')).not.toContainText(
    second.items[0].natural!.qualificationToken,
  );
  expect(
    (
      await request.get(fixture.url + '/api/sandbox/feed-deliveries/' + first.operationId, {
        headers: { Authorization: 'Bearer ' + fixture.otherToken },
      })
    ).status(),
  ).toBe(403);
  await page.getByText('browser-admin', { exact: true }).click();
  await page.getByText('退出登录', { exact: true }).click();
  await expect(page).toHaveURL(/\/login/);
  expect(await page.evaluate(() => sessionStorage.getItem('recsys.sandbox.feed.v1'))).toBeNull();
  expect(
    await page.evaluate(() => sessionStorage.getItem('recsys.sandbox.receipts.v1')),
  ).toBeNull();
  expect(
    await page.evaluate(() => sessionStorage.getItem('recsys.sandbox.natural.receipts.v1')),
  ).toBeNull();
});
test('原生hidden重置混排广告计时，离屏不产生自然或资金事实', async ({ request }) => {
  const browser = await nativeBrowser();
  try {
    await browser.page('Emulation.setDeviceMetricsOverride', {
      width: 1280,
      height: 450,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await browser.page('Page.addScriptToEvaluateOnNewDocument', {
      source: `localStorage.setItem('recsys_token',${JSON.stringify(fixture.token)});localStorage.setItem('recsys_username','browser-admin');localStorage.setItem('recsys_roles','["ADMIN"]');`,
    });
    await browser.page('Page.navigate', { url: 'http://127.0.0.1:4185/feed' });
    await expect
      .poll(() =>
        browser.evaluate<boolean>(
          `Array.from(document.querySelectorAll('button')).some(b=>b.textContent==='新交付'&&!b.disabled)`,
        ),
      )
      .toBe(true);
    await browser.evaluate(
      `const query=document.getElementById('query');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(query,'action');query.dispatchEvent(new Event('input',{bubbles:true}));`,
    );
    await browser.evaluate(
      `Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='新交付').click()`,
    );
    await expect
      .poll(() =>
        browser.evaluate<number>(`document.querySelectorAll('[data-testid^="creative-"]').length`),
      )
      .toBe(2);
    const id = await browser.evaluate<string>(
      `JSON.parse(sessionStorage.getItem('recsys.sandbox.feed.v1')).operationId`,
    );
    await new Promise((r) => setTimeout(r, 1200));
    expect(await facts(request, id)).toMatchObject({ ledgers: 0, naturalSlots: [] });
    await browser.evaluate(
      `document.querySelector('[data-testid="creative-2"]').scrollIntoView({block:'center'})`,
    );
    await expect
      .poll(
        () =>
          browser.evaluate<boolean>(
            `document.querySelector('[data-testid="feed-ad-2"]').innerText.includes('可见计时中')`,
          ),
        { intervals: [25] },
      )
      .toBe(true);
    await browser.send('Target.createTarget', { url: 'about:blank' });
    await expect.poll(() => browser.evaluate<string>('document.visibilityState')).toBe('hidden');
    await new Promise((r) => setTimeout(r, 1200));
    expect((await facts(request, id)).ledgers).toBe(0);
    await browser.send('Target.activateTarget', { targetId: browser.targetId });
    await expect.poll(() => browser.evaluate<string>('document.visibilityState')).toBe('visible');
    await new Promise((r) => setTimeout(r, 500));
    expect((await facts(request, id)).ledgers).toBe(0);
    await expect.poll(async () => (await facts(request, id)).ledgers).toBe(1);
  } finally {
    await browser.close();
  }
});
