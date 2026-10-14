import { describe, expect, it } from 'vitest';
import { adPriceUnit, formatBudgetEstimate } from '../adPricing';

describe('旧预算与报价展示契约', () => {
  it('保留已知零，缺失和非有限预算显示待确认', () => {
    expect(formatBudgetEstimate(0)).toBe('0.00');
    expect(formatBudgetEstimate(1.25)).toBe('1.25');
    for (const value of [null, undefined, NaN, Infinity]) {
      expect(formatBudgetEstimate(value)).toBe('待确认');
    }
  });

  it('按实际扣费事件标注单位，未知模式不冒充点击计费', () => {
    expect(adPriceUnit('CPC')).toBe('元/点击');
    expect(adPriceUnit('OCPM')).toBe('元/曝光');
    expect(adPriceUnit('CPA')).toBe('元/转化');
    expect(adPriceUnit('UNKNOWN')).toBe('单位待确认');
  });
});
