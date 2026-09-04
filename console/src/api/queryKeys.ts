/** 全站 React Query key 工厂:读侧与失效共用,避免散落字符串漂移。 */
export const queryKeys = {
  recommend: (p: unknown) => ['recommend', p] as const,
  search: (p: unknown) => ['search', p] as const,
  searchAds: (p: unknown) => ['search-ads', p] as const,
  feed: (p: unknown) => ['feed', p] as const,
  queryParse: (p: unknown) => ['query-parse', p] as const,
  recallLab: (p: unknown) => ['recall-lab', p] as const,
  experiment: () => ['experiment'] as const,
  advertisers: () => ['advertisers'] as const,
  advertiser: (id: number) => ['advertiser', id] as const,
  ads: (advertiserId: number) => ['ads', advertiserId] as const,
  ad: (id: number) => ['ad', id] as const,
  interests: (userId: number) => ['interests', userId] as const,
  reportIndex: () => ['report-index'] as const,
  reportFile: (name: string) => ['report-file', name] as const,
  systemOps: () => ['system-ops'] as const,
};
