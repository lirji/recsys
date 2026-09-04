/** 把广告/创意等 ID 当字符串展示,避免 Snowflake 被当成 JS number 再运算。 */
export function formatId(id: number | string | null | undefined): string {
  if (id == null || id === '') return '';
  if (typeof id === 'number' && (!Number.isFinite(id) || id === 0)) return '';
  if (typeof id === 'string' && (id === '0' || id === 'null')) return '';
  return String(id);
}
