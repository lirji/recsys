// 轻量导航 holder(类比 notify.ts):让「非 React」模块(client.ts 的 401 拦截器)能触发跳转到登录页。
// 顶层组件(App.tsx)在 Router 内用 useNavigate() 注册;未注册时兜底 window.location。
type RedirectFn = () => void;

let redirectFn: RedirectFn | null = null;

/** 顶层组件挂载时注入 react-router 的跳转能力。 */
export function setRedirectToLogin(fn: RedirectFn): void {
  redirectFn = fn;
}

/** 会话过期(401)时跳登录页。holder 未就绪则硬跳转兜底。 */
export function redirectToLogin(): void {
  if (redirectFn) redirectFn();
  else if (window.location.pathname !== '/login') window.location.assign('/login');
}

// —— 全站可导航目的地:单一事实源 ——
// 侧边菜单(AppLayout 的 Menu)与命令面板(⌘K CommandPalette)都从这里派生,保证「同源、不漂移」。
// 顺序 = 侧边菜单分组内的展示顺序;group = 分区显示名(命令面板据此分组/搜索);path = router.tsx 的 <Route path>。
// 只放「顶层可跳转目的地」(不含 /advertiser/ad/:adId 这类详情子路由),与侧边菜单一致。
export interface NavDestination {
  /** 路由 path,= router.tsx 的 <Route path> 且 = 菜单项 key */
  path: string;
  /** 中文标签(菜单与命令面板共用显示) */
  label: string;
  /** 所属分区(与侧边菜单分组名一致) */
  group: string;
  /** 额外搜索词(英文 / 拼音别名),命令面板模糊匹配用 */
  keywords?: string;
  /** 可见角色;缺省=全员。菜单与命令面板按登录身份过滤。 */
  roles?: Array<'ADMIN' | 'ADVERTISER' | 'USER'>;
}

// 按角色/领域重划的 5 大顶层域(group)。改 group 值即重划侧边菜单与命令面板分组,路由 path 不变。
// 域顺序在 AppLayout 的 GROUP_ORDER 里定义,两处 group 名必须逐字一致。
export const NAV_DESTINATIONS: NavDestination[] = [
  // —— 在线链路:推荐/搜索/广告/Feed 全链路在线调试 ——
  { path: '/recommend', label: '推荐', group: '在线链路', keywords: 'recommend rec tuijian' },
  { path: '/search', label: '搜索', group: '在线链路', keywords: 'search sousuo' },
  { path: '/search-ads', label: '搜索广告', group: '在线链路', keywords: 'search ads sem bidding guanggao' },
  { path: '/feed', label: '混排 Feed', group: '在线链路', keywords: 'feed mixed hunpai xinxiliu' },
  { path: '/query', label: 'Query 理解', group: '在线链路', keywords: 'query understanding nlp lijie' },
  { path: '/recall-lab', label: '召回沙盘', group: '在线链路', keywords: 'recall lab channel zhaohui shapan tongdao' },
  { path: '/strategy-lab', label: '策略对比台', group: '在线链路', keywords: 'strategy compare rank rerank celue duibi' },
  { path: '/user-interests', label: '冷启动兴趣', group: '在线链路', keywords: 'cold start interests lengqidong xingqu', roles: ['ADMIN'] },
  // —— 实验与增长 ——
  { path: '/experiment', label: '实验管理', group: '实验与增长', keywords: 'experiment ab test shiyan zengzhang', roles: ['ADMIN'] },
  { path: '/bucket-board', label: '桶对比大盘', group: '实验与增长', keywords: 'bucket board compare verdict tong duibi dapan jielun', roles: ['ADMIN'] },
  // —— 广告平台 ——
  { path: '/advertiser', label: '广告主 / 广告', group: '广告平台', keywords: 'advertiser ad guanggaozhu pingtai', roles: ['ADMIN', 'ADVERTISER'] },
  // —— 数据与模型(MLOps):离线评测/报表/模型/质量 ——
  { path: '/reports', label: '评测 / 报表', group: '数据与模型', keywords: 'reports eval metrics pingce baobiao mlops', roles: ['ADMIN', 'ADVERTISER'] },
  // —— 平台运维:总览/画像/诊断/告警 ——
  { path: '/overview', label: '系统总览', group: '平台运维', keywords: 'overview system dashboard zonglan yunwei' },
  { path: '/user360', label: '用户 360', group: '平台运维', keywords: 'user360 user profile yonghu huaxiang' },
  { path: '/diagnosis', label: '一键诊断', group: '平台运维', keywords: 'diagnosis health check zhenduan tijian', roles: ['ADMIN'] },
  { path: '/alerts', label: '告警面板', group: '平台运维', keywords: 'alerts alarm gaojing', roles: ['ADMIN'] },
  { path: '/ops', label: '热配置 / 作业', group: '平台运维', keywords: 'ops tuning jobs nacos redis zuoye', roles: ['ADMIN'] },
];

/** 当前路径所属导航分区;详情子路由(/advertiser/1/ads)归到最长前缀的顶层目的地。 */
export function navGroupForPath(pathname: string): string | undefined {
  const match = NAV_DESTINATIONS
    .filter((d) => pathname === d.path || pathname.startsWith(`${d.path}/`))
    .sort((a, b) => b.path.length - a.path.length)[0];
  return match?.group;
}

/** 顶栏 userId/scene 只在「在线链路」调试台出现,广告主/实验/运维页不消费这两个值。 */
export function showsDebugContext(pathname: string): boolean {
  return navGroupForPath(pathname) === '在线链路';
}
