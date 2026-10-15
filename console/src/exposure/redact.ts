const secrets = new Set([
  'qualificationtoken',
  'offertoken',
  'authorization',
  'accesstoken',
  'refreshtoken',
]);
/** 历史、复制、下载只消费脱敏快照；不修改用于当前采集的原响应。 */
export function redactExposureSecrets<T>(value: T): T {
  if (Array.isArray(value)) return value.map((item) => redactExposureSecrets(item)) as T;
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, child]) => [
        key,
        secrets.has(key.replace(/[_-]/g, '').toLowerCase())
          ? '[REDACTED]'
          : redactExposureSecrets(child),
      ]),
    ) as T;
  }
  return value;
}
