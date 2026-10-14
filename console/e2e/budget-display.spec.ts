import { expect, test } from '@playwright/test';
import type { AdvertiserView, SponsoredAd } from '../src/api/types';

// C01 的确定性响应夹具，只验证展示契约；所有 API 被隔离，不向真实账户发送请求。
const advertisers: AdvertiserView[] = [
  {
    advertiserId: 1,
    name: '已知分计数',
    status: 'active',
    dailyBudget: 100,
    spentToday: 1.25,
    remainingBudget: 98.75,
  },
  {
    advertiserId: 2,
    name: '未确认计数',
    status: 'active',
    dailyBudget: 100,
    spentToday: null,
    remainingBudget: null,
  },
];
const ads: SponsoredAd[] = ['CPC', 'CPM'].map((bidType, index) => ({
  adId: index + 1,
  advertiserId: 1,
  itemId: index + 10,
  bidwordId: 1,
  title: `报价示例 ${bidType}`,
  channel: 'KW_EXACT',
  bid: 2,
  quality: 1,
  relevance: 0.9,
  pctr: 0.1,
  pctrCalibrated: 0.1,
  ecpm: 0.2,
  chargedPrice: 1.25,
  position: index + 1,
  creativeId: 0,
  bidType,
}));

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('recsys_token', 'isolated-contract-fixture');
    localStorage.setItem('recsys_username', 'fixture-admin');
    localStorage.setItem('recsys_roles', '["ADMIN"]');
  });
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() !== 'GET') {
      await route.abort();
      return;
    }
    const result =
      path === '/api/advertiser'
        ? advertisers
        : path === '/api/advertiser/2'
          ? advertisers[1]
          : path === '/api/search-ads'
            ? { userId: 1, query: 'action', requestId: 'fixture', traceId: 'fixture', ads }
            : {};
    await route.fulfill({ json: result });
  });
});

test('预算未知不显示零进度，详情保留待确认', async ({ page }, testInfo) => {
  await page.goto('/advertiser');
  const known = page.getByRole('row').filter({ hasText: '已知分计数' });
  const unknown = page.getByRole('row').filter({ hasText: '未确认计数' });
  await expect(known).toContainText('1.25 / 剩 98.75');
  await expect(unknown).toContainText('待确认 / 剩 待确认');
  await expect(unknown.locator('.ant-progress')).toHaveCount(0);
  await page.screenshot({ path: testInfo.outputPath('budget-desktop.png'), fullPage: true });
  await unknown.getByRole('link', { name: '未确认计数' }).click();
  await expect(page.locator('.ant-descriptions')).toContainText('待确认');
  await page.getByRole('button', { name: '收起菜单' }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('button', { name: '展开菜单' })).toBeVisible();
  await expect
    .poll(() => page.locator('.shell-sider').evaluate((el) => el.getBoundingClientRect().width))
    .toBeLessThanOrEqual(1); // 收起后只保留壳层的 1px 边框。
  await page.screenshot({ path: testInfo.outputPath('budget-detail-mobile.png'), fullPage: true });
});

test('报价页面和重放不把排序分价差展示成实收', async ({ page }, testInfo) => {
  await page.goto('/search-ads');
  await expect(page.getByRole('table')).toContainText('排序分 / 计价单价');
  await expect(page.getByRole('table')).toContainText('元/点击');
  await expect(page.getByRole('table')).toContainText('元/曝光');
  await page.getByRole('button', { name: '竞价报价 · 按各广告计费单位' }).click();
  await page.getByRole('button', { name: '竞价链路逐步重放' }).click();
  for (let step = 0; step < 4; step++) {
    await page.getByRole('button', { name: '下一步' }).click();
  }
  await expect(page.getByText('计价单价 1.2500 元/点击')).toBeVisible();
  await expect(page.locator('body')).not.toContainText('实收');
  await expect(page.locator('body')).not.toContainText('省下价差');
  await page.evaluate(() => window.scrollTo(0, 0));
  // ECharts 的首帧插值不是最终报价高度，待动画结束后再保存视觉证据。
  await page.waitForTimeout(1100);
  await page.screenshot({ path: testInfo.outputPath('quotes-desktop.png'), fullPage: true });
});
