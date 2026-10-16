import { expect, test, type Page } from '@playwright/test';

// 所有请求在浏览器边界隔离；写操作只修改本测试闭包，不触达共享广告账户。
const id = '1306381397547552769';
const creativeId = '1306381397513998336';
const allowed = { createAdvertiser: true, reviewAds: true, editAds: true, manageAdvertiser: true };
const denied = {
  createAdvertiser: false,
  reviewAds: false,
  editAds: false,
  manageAdvertiser: false,
};
const ad = () => ({
  adId: Number(id),
  adIdText: id,
  advertiserId: 1,
  advertiserIdText: '1',
  itemId: 10,
  title: '审核中的广告',
  landingUrl: '/landing',
  qualityScore: 1,
  status: 'active',
  optimizationType: 'CPC',
  targetCpa: null,
  audienceId: null,
  hasEmbedding: false,
  creatives: [],
  bidwords: [],
  reviewStatus: 'pending_review',
  reviewReason: '机审通过，等待复核',
});
async function fixture(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('recsys_token', 'isolated-advertiser-fixture');
    localStorage.setItem('recsys_username', 'fixture-operator');
    localStorage.setItem('recsys_roles', '["ADVERTISER"]');
  });
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() !== 'GET') return route.abort();
    if (path.endsWith('/permissions')) return route.fulfill({ json: allowed });
    if (path === `/api/advertiser/ad/${id}`) return route.fulfill({ json: ad() });
    if (path.endsWith('/creative') || path.endsWith('/bidword')) return route.fulfill({ json: [] });
    return route.abort();
  });
}

test('广告编辑保留精确ID，清除定向失败可恢复；桌面和窄屏无溢出', async ({ page }, testInfo) => {
  await fixture(page);
  let writes = 0;
  let current = ad();
  await page.route(`**/api/advertiser/ad/${id}`, async (route) => {
    if (route.request().method() === 'PUT') {
      writes++;
      const body = route.request().postDataJSON();
      expect(body.audienceId).toBe('0');
      expect(body.title).toBe('输入必须保留');
      if (writes === 1)
        return route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE' } });
      current = { ...current, ...body, audienceId: null };
    }
    return route.fulfill({ json: current });
  });
  await page.goto(`/advertiser/ad/${id}`);
  await expect(page.getByText('待审核', { exact: true })).toBeVisible();
  await expect(page.getByText('投放中', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: /^编\s*辑$/ }).click();
  const dialog = page.getByRole('dialog', { name: '编辑广告' });
  await dialog.getByLabel('标题', { exact: true }).fill('输入必须保留');
  await dialog.getByRole('button', { name: /确\s*定|OK/ }).click();
  await expect(page.getByText('[SERVICE_UNAVAILABLE]', { exact: false })).toBeVisible();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel('标题', { exact: true })).toHaveValue('输入必须保留');
  await dialog.getByRole('button', { name: /确\s*定|OK/ }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByText('输入必须保留', { exact: true }).first()).toBeVisible();
  expect(writes).toBe(2);
  await page.screenshot({ path: testInfo.outputPath('advertiser-desktop.png'), fullPage: true });
  await page.getByRole('button', { name: '收起菜单' }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth))
    .toBeLessThanOrEqual(390);
  await page.screenshot({ path: testInfo.outputPath('advertiser-mobile.png'), fullPage: true });
});

test('驳回失败保留原因，权限撤销后关闭所有写入口', async ({ page }) => {
  await fixture(page);
  let revoked = false;
  await page.route('**/permissions', (route) =>
    route.fulfill({ json: revoked ? denied : allowed }),
  );
  await page.route(`**/api/advertiser/ad/${id}/review?*`, async (route) => {
    expect(new URL(route.request().url()).searchParams.get('reason')).toBe('需要修改落地页');
    revoked = true;
    return route.fulfill({ status: 403, json: { code: 'FORBIDDEN' } });
  });
  await page.goto(`/advertiser/ad/${id}`);
  await page.getByRole('button', { name: /^驳\s*回$/ }).click();
  const dialog = page.getByRole('dialog', { name: '驳回广告' });
  await dialog.getByLabel('驳回原因').fill('需要修改落地页');
  await dialog.getByRole('button', { name: /确\s*定|OK/ }).click();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel('驳回原因')).toHaveValue('需要修改落地页');
  await expect(dialog.getByRole('button', { name: /确\s*定|OK/ })).toBeDisabled();
  await dialog.getByRole('button', { name: /取\s*消|Cancel/ }).click();
  await expect(page.getByRole('button', { name: /^编\s*辑$/ })).toBeDisabled();
  await expect(page.getByRole('button', { name: '审核通过' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '新增创意' })).toBeDisabled();
});

test('创意加载失败可重试，编辑使用未经取整的原始ID', async ({ page }) => {
  await fixture(page);
  let healthy = false;
  const creative = {
    creativeId: Number(creativeId),
    creativeIdText: creativeId,
    adId: Number(id),
    adIdText: id,
    title: '精确创意',
    landingUrl: '/creative',
    status: 'approved',
  };
  await page.route(`**/api/advertiser/ad/${id}/creative`, (route) =>
    healthy
      ? route.fulfill({ json: [creative] })
      : route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE' } }),
  );
  let updated = false;
  await page.route(`**/api/advertiser/creative/${creativeId}`, async (route) => {
    expect(route.request().method()).toBe('PUT');
    expect(route.request().postDataJSON().title).toBe('更新后的精确创意');
    creative.title = '更新后的精确创意';
    updated = true;
    return route.fulfill({ json: creative });
  });
  await page.goto(`/advertiser/ad/${id}`);
  const card = page
    .locator('.ant-card')
    .filter({ has: page.getByRole('button', { name: 'picture 创意', exact: true }) });
  await expect(card.getByRole('alert')).toContainText('[SERVICE_UNAVAILABLE]');
  await expect(card.getByRole('table')).toHaveCount(0);
  healthy = true;
  await card.getByRole('button', { name: /^重\s*试$/ }).click();
  await expect(card.getByRole('row').filter({ hasText: '精确创意' })).toContainText(creativeId);
  await card.getByRole('button', { name: /^编\s*辑$/ }).click();
  const dialog = page.getByRole('dialog', { name: '编辑创意' });
  await dialog.getByLabel('标题', { exact: true }).fill('更新后的精确创意');
  await dialog.getByRole('button', { name: /确\s*定|OK/ }).click();
  await expect(dialog).not.toBeVisible();
  expect(updated).toBe(true);
});

test('旧响应缺少审核状态时显示未知，权限失败可恢复', async ({ page }) => {
  await fixture(page);
  let healthy = false;
  await page.route('**/permissions', (route) =>
    healthy
      ? route.fulfill({ json: denied })
      : route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE' } }),
  );
  await page.route(`**/api/advertiser/ad/${id}`, (route) =>
    route.fulfill({ json: { ...ad(), reviewStatus: null, reviewReason: null } }),
  );
  await page.goto(`/advertiser/ad/${id}`);
  await expect(page.getByText('审核状态未知', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '审核通过' })).toBeDisabled();
  healthy = true;
  await page.getByRole('button', { name: '重试权限查询' }).click();
  await expect(page.getByText('操作权限暂时无法确认')).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^编\s*辑$/ })).toBeDisabled();
});
