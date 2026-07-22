import { describe, expect, it } from 'vitest'
import { resolvePortalLaunch, sanitizeInternalPath } from '../portalLaunch'

describe('portal launch contract', () => {
  it('解析显式 portal auto 与站内 returnTo', () => {
    expect(resolvePortalLaunch('?source=portal&auto=1&returnTo=%2Fadvertiser%2F5%3Ftab%3Dads'))
      .toEqual({ returnTo: '/advertiser/5?tab=ads' })
  })

  it.each(['', '?source=other&auto=1', '?source=portal&auto=0'])('普通入口不自动跳: %s', (search) => {
    expect(resolvePortalLaunch(search)).toBeNull()
  })

  it('拒绝重复的控制参数，避免代理与浏览器解析歧义', () => {
    expect(resolvePortalLaunch('?source=portal&source=other&auto=1')).toBeNull()
    expect(resolvePortalLaunch('?source=portal&auto=1&auto=1')).toBeNull()
    expect(resolvePortalLaunch('?source=portal&auto=1&returnTo=%2Foverview&returnTo=%2Fadmin')).toBeNull()
  })

  it.each(['//evil.com', 'https://evil.com', '/\\evil', '/ok\u0000bad'])('拒绝危险 returnTo %s', (value) => {
    expect(sanitizeInternalPath(value)).toBeNull()
  })

  it('危险或缺失 returnTo 在 portal auto 下回退项目概览', () => {
    expect(resolvePortalLaunch('?source=portal&auto=1&returnTo=%2F%2Fevil.com'))
      .toEqual({ returnTo: '/overview' })
    expect(resolvePortalLaunch('?source=portal&auto=1')).toEqual({ returnTo: '/overview' })
  })
})
