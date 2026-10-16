import { beforeEach, describe, expect, it, vi } from 'vitest';
const http = vi.hoisted(() => ({ post: vi.fn(), delete: vi.fn(), get: vi.fn() }));
vi.mock('../client', () => ({ http }));
import { clearOverride, setGlobalEnabled, setLayerEnabled, setVariantWeight } from '../experiment';
import { ClientError, formatError } from '../errors';

beforeEach(() => vi.resetAllMocks());
describe('实验写入应答', () => {
  const writes = [
    () => setGlobalEnabled(true),
    () => setLayerEnabled('rank', false),
    () => setVariantWeight('rank', 'v1', 10),
    clearOverride,
  ];
  it.each([
    null,
    undefined,
    {},
    { ok: 'true' },
    { ok: false, reason: 'PRIVATE_SQL' },
    { ok: false, code: 'UNKNOWN_NEW_CODE', reason: 'PRIVATE_SQL' },
  ])('拒绝缺失或不确定应答，不能展示原reason：%j', async (data) => {
    http.post.mockResolvedValue({ data });
    http.delete.mockResolvedValue({ data });
    for (const write of writes) {
      await expect(write()).rejects.toMatchObject({ code: 'EXPERIMENT_WRITE_UNCONFIRMED' });
    }
  });
  it('只认可固定拒绝码并保留成功协议', async () => {
    http.post.mockResolvedValue({
      data: { ok: false, code: 'EXPERIMENT_STORE_UNAVAILABLE', reason: 'PRIVATE' },
    });
    await expect(setGlobalEnabled(true)).rejects.toEqual(
      new ClientError('EXPERIMENT_STORE_UNAVAILABLE'),
    );
    http.post.mockResolvedValue({ data: { ok: true, field: 'enabled', value: 'true' } });
    await expect(setGlobalEnabled(true)).resolves.toEqual({
      ok: true,
      field: 'enabled',
      value: 'true',
    });
    expect(formatError('EXPERIMENT_WRITE_UNCONFIRMED')).toContain('可能已有部分配置生效');
  });
});
