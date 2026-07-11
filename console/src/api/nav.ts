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
}

export const NAV_DESTINATIONS: NavDestination[] = [
  { path: '/overview', label: '系统总览', group: '项目', keywords: 'overview system dashboard zonglan' },
  { path: '/user360', label: '用户 360', group: '项目', keywords: 'user360 user profile yonghu huaxiang' },
  { path: '/diagnosis', label: '一键诊断', group: '项目', keywords: 'diagnosis health check zhenduan tijian' },
  { path: '/alerts', label: '告警面板', group: '项目', keywords: 'alerts alarm gaojing' },
  { path: '/recommend', label: '推荐', group: '在线调试台', keywords: 'recommend rec tuijian' },
  { path: '/search', label: '搜索', group: '在线调试台', keywords: 'search sousuo' },
  { path: '/search-ads', label: '搜索广告', group: '在线调试台', keywords: 'search ads sem bidding guanggao' },
  { path: '/feed', label: '混排 Feed', group: '在线调试台', keywords: 'feed mixed hunpai xinxiliu' },
  { path: '/query', label: 'Query 理解', group: '在线调试台', keywords: 'query understanding nlp lijie' },
  { path: '/experiment', label: '实验管理', group: '在线调试台', keywords: 'experiment ab test shiyan' },
  { path: '/user-interests', label: '冷启动兴趣', group: '在线调试台', keywords: 'cold start interests lengqidong xingqu' },
  { path: '/advertiser', label: '广告主 / 广告', group: '广告主后台', keywords: 'advertiser ad guanggaozhu' },
  { path: '/reports', label: '评测 / 报表', group: '离线报表', keywords: 'reports eval metrics pingce baobiao' },
];
