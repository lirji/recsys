import { describe, expect, it } from 'vitest';
import { parseActionDelivery, validLongId } from '../sandboxActions';
describe('CPC 真实契约边界', () => {
  it('Java long 字符串与零创意 ID 保持原值；跨模式/串操作/串输入拒绝', () => {
    const input = { userId: '9223372036854775807', query: 'q', scene: 's', slots: 1 },
      operationId = crypto.randomUUID(),
      childId = crypto.randomUUID();
    const raw = {
      ...input,
      operationId,
      state: 'COMPLETE',
      trafficClass: 'SANDBOX',
      items: [
        {
          slot: 1,
          childId,
          adId: input.userId,
          itemId: input.userId,
          advertiserId: input.userId,
          creativeId: '0',
          title: '数据库创意',
          bidType: 'OCPC',
          priceUnit: 'PER_CLICK',
          pricingVersion: 'CLICK_GSP_CNY6_V1',
          preparation: {
            childId,
            status: 'QUALIFIED',
            amount: '0.123456',
            qualificationToken: 'opaque-proof',
            expiresAt: new Date().toISOString(),
          },
        },
      ],
    };
    expect(parseActionDelivery(raw, operationId, input).items[0].adId).toBe(input.userId);
    expect(validLongId('9223372036854775808')).toBe(false);
    expect(validLongId(Number('9223372036854775807'))).toBe(false);
    expect(() => parseActionDelivery(raw, crypto.randomUUID(), input)).toThrow();
    expect(() => parseActionDelivery({ ...raw, userId: '1' }, operationId, input)).toThrow();
    expect(() =>
      parseActionDelivery(
        { ...raw, items: [{ ...raw.items[0], priceUnit: 'PER_CONVERSION' }] },
        operationId,
        input,
      ),
    ).toThrow();
    expect(() =>
      parseActionDelivery({ ...raw, items: [{ ...raw.items[0], slot: 0 }] }, operationId, input),
    ).toThrow();
  });
  it('COMPLETE 不能缺准备结果，202 PREPARING 可保留未准备位置', () => {
    const input = { userId: '1', query: 'q', scene: 's', slots: 1 },
      id = crypto.randomUUID();
    const raw = {
      ...input,
      operationId: id,
      trafficClass: 'SANDBOX',
      state: 'PREPARING',
      items: [],
    };
    expect(parseActionDelivery(raw, id, input).state).toBe('PREPARING');
    expect(() => parseActionDelivery({ ...raw, trafficClass: 'PRODUCTION' }, id, input)).toThrow();
  });
});
