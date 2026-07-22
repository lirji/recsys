export interface PortalLaunch {
  returnTo: string
}

/** 只接受站内单斜杠绝对路径，拒绝开放重定向和路径解析歧义。 */
export function sanitizeInternalPath(raw: unknown): string | null {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//')) return null
  if (raw.includes('\\') || [...raw].some((character) => {
    const code = character.codePointAt(0) ?? 0
    return code <= 0x1f || code === 0x7f
  })) return null
  return raw
}

/** 仅显式 portal auto 请求触发；普通 /login 保持人工点击。 */
export function resolvePortalLaunch(search: string): PortalLaunch | null {
  const query = new URLSearchParams(search)
  if (query.getAll('source').length !== 1 || query.get('source') !== 'portal') return null
  if (query.getAll('auto').length !== 1 || query.get('auto') !== '1') return null
  if (query.getAll('returnTo').length > 1) return null
  return { returnTo: sanitizeInternalPath(query.get('returnTo')) ?? '/overview' }
}
