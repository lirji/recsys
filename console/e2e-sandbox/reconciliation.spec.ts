import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
const fixture = JSON.parse(readFileSync(process.env.RECONCILIATION_FIXTURE_INFO!, 'utf8')) as {
  url: string;
  token: string;
  otherToken: string;
  day: string;
  advertiserId: string;
};
const path = `/advertiser/${fixture.advertiserId}/report?budgetDate=${fixture.day}`;
test('离线事件报表明确来源及未知水位，保留原明细入口', async ({ page }, info) => {
  await page.addInitScript(() => {
    localStorage.setItem('recsys_token', 'isolated-offline-report-fixture');
    localStorage.setItem('recsys_username', 'fixture-admin');
    localStorage.setItem('recsys_roles', '["ADMIN"]');
  });
  const fileName = 'ad-report-source.csv';
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      const json =
        pathname === '/api/console/report/index'
          ? [
              {
                fileName,
                category: 'ad-report',
                timestamp: 'source-fixture',
                sizeBytes: 20,
                modifiedAt: 1,
              },
            ]
          : pathname === '/api/console/report/file'
            ? { fileName, category: 'ad-report', columns: ['position', 'impressions'], rows: [] }
            : {};
      await route.fulfill({ json });
    },
  );
  await page.goto('/reports/ad-report');
  await expect(page.getByText('离线事件统计（ad_event）', { exact: true })).toBeVisible();
  await expect(page.getByText(/当前文件未提供资金账本核对结果或全局消息水位/)).toBeVisible();
  await page.getByRole('button').filter({ hasText: '明细' }).click();
  await expect(page.getByRole('table')).toContainText('position');
  await page.screenshot({
    path: info.outputPath('offline-source-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  });
});
test('真实账户日读取、键盘查询刷新、投影追平、撤权恢复与默认关闭', async ({
  page,
  request,
}, info) => {
  const before = await (await request.get(fixture.url + '/fixture/money')).json();
  let reads = 0;
  page.on('request', (r) => {
    if (r.url().includes('/sandbox-reconciliation/')) reads++;
  });
  await page.addInitScript((token) => {
    localStorage.setItem('recsys_token', token);
    localStorage.setItem('recsys_username', 'report-viewer');
    localStorage.setItem('recsys_roles', '["ADVERTISER"]');
  }, fixture.token);
  // 壳层及旧报表独立替身只支持页面导航；新资金接口必须经过真实HTTP/RPC/PG。
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      if (route.request().url().includes('/sandbox-reconciliation/')) await route.continue();
      else await route.fulfill({ json: route.request().url().endsWith('/report') ? [] : {} });
    },
  );
  await page.goto(path);
  await expect(page.getByText('历史事件统计（全期）', { exact: true })).toBeVisible();
  if (info.project.name === 'default-off') {
    await expect(page.getByText('Sandbox 账户日资金', { exact: true })).toHaveCount(0);
    await page.reload();
    await expect(page.getByText('历史事件统计（全期）', { exact: true })).toBeVisible();
    expect(reads).toBe(0);
    return;
  }
  await expect(page.getByText('0.600006', { exact: true })).toBeVisible();
  await expect(page.getByText('122.523450', { exact: true })).toBeVisible();
  await expect(page.getByText('资金核对一致', { exact: true })).toBeVisible();
  await expect(page.getByText('尚未追平', { exact: true })).toHaveCount(3);
  await page.locator('.ant-table-row-expand-icon').first().click();
  await expect(page.getByText('缺失投影事实笔数', { exact: true })).toBeVisible();
  await page.screenshot({
    path: info.outputPath('report-desktop.png'),
    fullPage: true,
    animations: 'disabled',
  });
  const day = page.getByLabel('原预算日（Asia/Shanghai）');
  const initial = reads;
  await day.fill('2026-02-31');
  await day.press('Enter');
  await expect(page.getByText(/请输入有效日期/)).toBeVisible();
  expect(reads).toBe(initial);
  await day.fill('2000-01-01');
  await day.press('Enter');
  await expect(page.getByText('该预算日尚未建立账户', { exact: true })).toBeVisible();
  await expect(page.getByText('0.600006', { exact: true })).toHaveCount(0);
  await day.fill(fixture.day);
  await day.press('Enter');
  await expect(page.getByText('0.600006', { exact: true })).toBeVisible();
  const refreshed = page.waitForResponse(
    (r) => r.url().includes('/sandbox-reconciliation/') && r.status() === 200,
  );
  await request.post(fixture.url + '/fixture/project');
  await page.getByRole('button', { name: '刷新快照', exact: true }).focus();
  await page.keyboard.press('Enter');
  await refreshed;
  await expect(page.getByText('已追平', { exact: true })).toHaveCount(3);
  await page.getByRole('button', { name: '收起菜单' }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBe(390);
  await page.screenshot({
    path: info.outputPath('report-narrow.png'),
    fullPage: true,
    animations: 'disabled',
  });
  for (const mode of ['deny', 'failure']) {
    await request.post(fixture.url + '/fixture/authz/' + mode);
    const denied = page.waitForResponse(
      (r) =>
        r.url().includes('/sandbox-reconciliation/') &&
        r.status() === (mode === 'deny' ? 403 : 503),
    );
    await page.getByRole('button', { name: '刷新快照', exact: true }).click();
    await denied;
    await expect(page.getByText(/当前未展示资金快照/)).toBeVisible();
    await expect(page.getByText('0.600006', { exact: true })).toHaveCount(0);
    await request.post(fixture.url + '/fixture/authz/allow');
    await page.getByRole('button', { name: '刷新快照', exact: true }).click();
    await expect(page.getByText('0.600006', { exact: true })).toBeVisible();
  }
  expect(await (await request.get(fixture.url + '/fixture/money')).json()).toEqual(before);
});
