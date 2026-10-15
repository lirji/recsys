import { expect, test } from '@playwright/test';
import type { ReportTable } from '../src/api/types';

// CSV协议来自EC06c1；网络完全隔离，只证明真实浏览器展示与交互。
const healthyRows = [
  ['item_embedding_coverage', '1'],
  ['user_embedding_coverage', '1'],
  ['pctr_ece', '0.01'],
  ['category_psi_7d', '0.02'],
  ['breaches', '0'],
  ['check_embedding_status', 'PASSED'],
  ['check_calibration_status', 'PASSED'],
  ['check_category_status', 'PASSED'],
];
const reports: Record<string, ReportTable> = Object.fromEntries(
  ['failed', 'healthy', 'empty', 'legacy', 'unknown'].map((kind) => {
    let rows = healthyRows.map((row) => [...row]);
    if (kind === 'failed')
      rows = rows.map((row) => (row[0] === 'check_category_status' ? [row[0], 'FAILED'] : row));
    if (kind === 'empty')
      rows = rows.map((row) => (row[0].startsWith('check_') ? [row[0], 'NO_DATA'] : row));
    if (kind === 'legacy') rows = rows.filter((row) => !row[0].startsWith('check_'));
    if (kind === 'unknown')
      rows = rows.map((row) =>
        row[0] === 'check_category_status' ? [row[0], 'private SQL password'] : row,
      );
    const fileName = `data-quality-${kind}.csv`;
    return [kind, { fileName, category: 'data-quality', columns: ['metric', 'value'], rows }];
  }),
);

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('recsys_token', 'isolated-quality-fixture');
    localStorage.setItem('recsys_username', 'fixture-admin');
    localStorage.setItem('recsys_roles', '["ADMIN"]');
  });
  await page.route('**/api/**', async (route) => {
    if (route.request().method() !== 'GET') return route.abort();
    const url = new URL(route.request().url());
    const result =
      url.pathname === '/api/console/report/index'
        ? Object.values(reports).map((report) => ({
            category: report.category,
            fileName: report.fileName,
            timestamp: report.fileName,
            sizeBytes: 300,
            modifiedAt: 1,
          }))
        : url.pathname === '/api/console/report/file'
          ? Object.values(reports).find(
              (report) => report.fileName === url.searchParams.get('name'),
            )
          : {};
    await route.fulfill({ json: result });
  });
});

test('部分失败可查看明细，切换完整报表后恢复健康展示', async ({ page }, testInfo) => {
  await page.goto('/reports/data-quality');
  await expect(
    page.getByRole('alert').filter({ hasText: '[JOB_DATA_QUALITY_CHECK_FAILED]' }),
  ).toBeVisible();
  await expect(page.getByText('类目分布：检查失败')).toBeVisible();
  await expect(page.getByText('三项检查已完成，无越阈值项')).toHaveCount(0);
  await page.waitForTimeout(1100);
  for (const canvas of await page.locator('.echarts-for-react canvas').all()) {
    expect((await canvas.boundingBox())!.width).toBeGreaterThan(200);
  }
  await page.screenshot({
    path: testInfo.outputPath('quality-failed-desktop.png'),
    fullPage: true,
  });
  await page.getByRole('button').filter({ hasText: '明细' }).click();
  await expect(page.getByRole('table')).toContainText('check_category_status');
  await expect(page.getByRole('table')).toContainText('FAILED');
  const files = page.getByRole('combobox', { name: '报表文件' });
  await files.focus();
  await files.press('ArrowDown');
  await page
    .locator('.ant-select-item-option')
    .filter({ hasText: 'data-quality-healthy.csv' })
    .click();
  await expect(page).toHaveURL(/file=data-quality-healthy.csv/);
  await expect(page.getByText('三项检查已完成，无越阈值项')).toBeVisible();
  await expect(page.getByText('类目分布：通过')).toBeVisible();
  await page.waitForTimeout(1100);
  for (const canvas of await page.locator('.echarts-for-react canvas').all()) {
    expect((await canvas.boundingBox())!.width).toBeGreaterThan(200);
  }
  await page.screenshot({
    path: testInfo.outputPath('quality-healthy-desktop.png'),
    fullPage: true,
  });
});

test('全空和旧报表不假绿，未知状态在窄屏安全展示', async ({ page }, testInfo) => {
  for (const kind of ['empty', 'legacy', 'unknown']) {
    await page.goto(`/reports/data-quality?file=${reports[kind].fileName}`);
    await expect(
      page.getByRole('alert').filter({ hasText: '[JOB_DATA_QUALITY_INCOMPLETE]' }),
    ).toBeVisible();
    await expect(page.getByText('三项检查已完成，无越阈值项')).toHaveCount(0);
    await expect(page.getByLabel('巡检检查状态')).not.toContainText('private SQL');
  }
  await page.getByRole('button', { name: '收起菜单' }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByText('类目分布：状态未知')).toBeVisible();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth))
    .toBeLessThanOrEqual(390);
  await page.waitForTimeout(1100);
  for (const canvas of await page.locator('.echarts-for-react canvas').all()) {
    expect((await canvas.boundingBox())!.width).toBeGreaterThan(200);
  }
  await page.screenshot({
    path: testInfo.outputPath('quality-unknown-mobile.png'),
    fullPage: true,
  });
});
