import { describe, it, expect } from 'vitest';
import { toCsv } from '../download';

// toCsv 是纯函数(列 + 行 → RFC4180 风格 CSV 字符串)。重点验证转义规则:
// 含逗号/引号/换行的单元格必须整体加引号,内部引号翻倍。
describe('toCsv', () => {
  it('serializes plain columns and rows without quoting', () => {
    const csv = toCsv(['a', 'b'], [
      ['1', '2'],
      ['3', '4'],
    ]);
    expect(csv).toBe('a,b\n1,2\n3,4');
  });

  it('quotes cells containing a comma', () => {
    const csv = toCsv(['name'], [['Doe, John']]);
    expect(csv).toBe('name\n"Doe, John"');
  });

  it('escapes embedded double quotes by doubling them', () => {
    const csv = toCsv(['q'], [['say "hi"']]);
    expect(csv).toBe('q\n"say ""hi"""');
  });

  it('quotes cells containing a newline', () => {
    const csv = toCsv(['multi'], [['line1\nline2']]);
    expect(csv).toBe('multi\n"line1\nline2"');
  });

  it('quotes header cells that need escaping too', () => {
    const csv = toCsv(['a,b', 'c'], [['1', '2']]);
    expect(csv).toBe('"a,b",c\n1,2');
  });

  it('produces only the header row when there are no data rows', () => {
    expect(toCsv(['x', 'y'], [])).toBe('x,y');
  });
});
