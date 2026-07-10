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
