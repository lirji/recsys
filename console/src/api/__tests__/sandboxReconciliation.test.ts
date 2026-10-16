import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CanceledError } from 'axios';
import {
  parseSandboxReconciliation,
  sandboxReconciliation,
  validBudgetDate,
} from '../sandboxReconciliation';
import { reportFixture, REPORT_ADVERTISER, REPORT_DAY } from './sandboxReconciliation.fixture';

const fake = vi.hoisted(() => ({ get: vi.fn(), resolve: vi.fn(), epoch: 0, scope: 'actor-a' }));
vi.mock('../client', () => ({ http: { get: fake.get } }));
vi.mock('../../auth/oidc', () => ({ resolveToken: fake.resolve }));
vi.mock('../../exposure/identity', () => ({
  exposureEpoch: () => fake.epoch,
  exposureAuthScope: () => fake.scope,
}));
beforeEach(() => {
  fake.get.mockReset();
  fake.resolve.mockResolvedValue('token');
  fake.epoch = 0;
  fake.scope = 'actor-a';
});

describe('账户日字符串契约与身份隔离', () => {
  it('微金额、大额、长ID和负差额保持原文，缺失账户不是零账户', () => {
    const raw = reportFixture();
    raw.fundsStatus = 'MISMATCH';
    raw.heldDelta = '-0.000001';
    raw.account!.limitAmount = '9007199254740993.123456';
    raw.account!.version = '9223372036854775807';
    expect(parseSandboxReconciliation(raw, REPORT_ADVERTISER, REPORT_DAY)).toEqual(raw);
    const absent = {
      ...raw,
      account: null,
      fundsStatus: 'ACCOUNT_MISSING',
      heldDelta: null,
      chargedDelta: null,
    };
    expect(parseSandboxReconciliation(absent, REPORT_ADVERTISER, REPORT_DAY).account).toBeNull();
    expect(validBudgetDate('2024-02-29')).toBe(true);
    expect(validBudgetDate('2026-02-29')).toBe(false);
    expect(validBudgetDate('0000-01-01')).toBe(false);
  });
  it('坏范围、数字金额/计数、缺字段、未知枚举和时间必须失败', () => {
    const good = reportFixture();
    const bad = [
      { ...good, schemaVersion: 2 },
      { ...good, currency: 'USD' },
      { ...good, trafficClass: 'PRODUCTION' },
      { ...good, scope: { advertiserId: Number(REPORT_ADVERTISER), budgetDate: REPORT_DAY } },
      { ...good, account: { ...good.account, heldAmount: 0 } },
      { ...good, account: { ...good.account, version: 7 } },
      { ...good, account: null },
      { ...good, heldDelta: undefined },
      { ...good, observedAt: 1792128000 },
      { ...good, observedAt: '2026-02-31T00:00:00Z' },
      { ...good, families: good.families.slice(1) },
      {
        ...good,
        families: [{ ...good.families[0], projectionStatus: 'FUTURE' }, ...good.families.slice(1)],
      },
      { ...good, families: [{ ...good.families[0], factCount: 0 }, ...good.families.slice(1)] },
      {
        ...good,
        families: [{ ...good.families[0], lastAppliedAt: undefined }, ...good.families.slice(1)],
      },
    ];
    bad.forEach((value) =>
      expect(() => parseSandboxReconciliation(value, REPORT_ADVERTISER, REPORT_DAY)).toThrow(),
    );
    expect(() => parseSandboxReconciliation(good, '1', REPORT_DAY)).toThrow();
    expect(() => parseSandboxReconciliation(good, REPORT_ADVERTISER, '2026-10-15')).toThrow();
  });
  it('请求带范围/epoch和取消信号，返回后再次拒绝旧身份', async () => {
    const identity = { scope: fake.scope, epoch: fake.epoch };
    const abort = new AbortController();
    fake.get.mockResolvedValue({ data: reportFixture() });
    await expect(
      sandboxReconciliation(REPORT_ADVERTISER, REPORT_DAY, identity, abort.signal),
    ).resolves.toEqual(reportFixture());
    expect(fake.get).toHaveBeenCalledWith(
      `/api/advertiser/${REPORT_ADVERTISER}/sandbox-reconciliation/${REPORT_DAY}`,
      expect.objectContaining({
        expectedExposureScope: 'actor-a',
        expectedExposureEpoch: 0,
        signal: abort.signal,
      }),
    );
    fake.get.mockImplementation(async () => {
      fake.epoch += 1;
      return { data: reportFixture() };
    });
    await expect(
      sandboxReconciliation(REPORT_ADVERTISER, REPORT_DAY, identity),
    ).rejects.toBeInstanceOf(CanceledError);
    fake.epoch = 0;
    fake.get.mockImplementation(async () => {
      fake.scope = 'actor-b';
      return { data: reportFixture() };
    });
    await expect(
      sandboxReconciliation(REPORT_ADVERTISER, REPORT_DAY, identity),
    ).rejects.toBeInstanceOf(CanceledError);
  });
});
