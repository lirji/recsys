/** 旧计数只供展示；未知与已知零必须区分，不能让运营误认为还有完整预算。 */
export function formatBudgetEstimate(value: number | null | undefined): string {
  return value != null && Number.isFinite(value) ? value.toFixed(2) : '待确认';
}

/** 单位跟随当前服务端实际扣费事件，不凭 CPM 名称推断每千次，也不推断已经收费。 */
export function adPriceUnit(bidType: string): string {
  switch (bidType) {
    case 'CPC':
    case 'OCPC':
      return '元/点击';
    case 'CPM':
    case 'OCPM':
      return '元/曝光';
    case 'CPA':
      return '元/转化';
    default:
      return '单位待确认';
  }
}
