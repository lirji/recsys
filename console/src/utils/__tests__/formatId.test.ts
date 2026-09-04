import { describe, expect, it } from 'vitest';
import { formatId } from '../formatId';

describe('formatId', () => {
  it('空值与 0 不展示', () => {
    expect(formatId(null)).toBe('');
    expect(formatId(undefined)).toBe('');
    expect(formatId(0)).toBe('');
    expect(formatId('')).toBe('');
  });

  it('数字与字符串都当文本', () => {
    expect(formatId(9)).toBe('9');
    expect(formatId('1282462391199600640')).toBe('1282462391199600640');
  });
});
