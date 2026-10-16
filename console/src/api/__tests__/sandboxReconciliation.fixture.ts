import type { SandboxReconciliation } from '../sandboxReconciliation';

// 仅契约测试数据；产品页面始终从真实HTTP读取，浏览器验收另由独占PG生成三族事实。
export const REPORT_ADVERTISER = '9223372036854775807';
export const REPORT_DAY = '2026-10-16';
export function reportFixture(day = REPORT_DAY): SandboxReconciliation {
  return {
    schemaVersion: 1,
    trafficClass: 'SANDBOX',
    currency: 'CNY',
    scope: { advertiserId: REPORT_ADVERTISER, budgetDate: day },
    observedAt: '2026-10-16T00:01:02.123456Z',
    fundsStatus: 'BALANCED',
    heldDelta: '0.000000',
    chargedDelta: '0.000000',
    account: {
      limitAmount: '123.123456',
      heldAmount: '0.000000',
      chargedAmount: '0.600006',
      availableAmount: '122.523450',
      version: '7',
    },
    families: (['IMPRESSION', 'CLICK', 'CONVERSION'] as const).map((family, i) => ({
      family,
      heldCount: '0',
      heldAmount: '0.000000',
      expiredHeldCount: '0',
      ledgerCount: '1',
      ledgerAmount: ['0.100001', '0.200002', '0.300003'][i]!,
      missingLedgerCount: '0',
      invalidLedgerCount: '0',
      pendingOutboxCount: '1',
      deadOutboxCount: '0',
      missingOutboxCount: '0',
      oldestPendingAt: '2026-10-16T00:00:01Z',
      factCount: '0',
      factAmount: '0.000000',
      missingFactCount: '1',
      invalidFactCount: '0',
      unexpectedFactCount: '0',
      lastAppliedAt: null,
      summaryPresent: false,
      summaryCount: '0',
      summaryAmount: '0.000000',
      projectionStatus: 'LAGGING',
    })),
  };
}
