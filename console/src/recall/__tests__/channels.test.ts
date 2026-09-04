import { describe, expect, it } from 'vitest';
import { decodeRecallChannels, encodeRecallChannels } from '../channels';

describe('recall channel codec', () => {
  it('round-trips CSV and drops blanks', () => {
    expect(encodeRecallChannels(['vector', ' HOT ', ''])).toBe('VECTOR,HOT');
    expect(decodeRecallChannels('VECTOR, LEXICAL')).toEqual(['VECTOR', 'LEXICAL']);
    expect(decodeRecallChannels('')).toEqual([]);
    expect(encodeRecallChannels([])).toBe('');
  });
});
