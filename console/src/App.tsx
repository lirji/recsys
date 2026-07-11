import { useEffect } from 'react';
import { App as AntdApp } from 'antd';
import { useLocation, useNavigate } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import AppRoutes, { RequireAuth } from './router';
import LoginPage from './pages/LoginPage';
import ErrorBoundary from './components/ErrorBoundary';
import { setMessageApi } from './api/notify';
import { setRedirectToLogin } from './api/nav';

export default function App() {
  const { message } = AntdApp.useApp();
  const navigate = useNavigate();
  const location = useLocation();

  // 把带 <App> 主题上下文的 message 实例注册给非组件模块(client.ts 的 403 提示等)。
  useEffect(() => {
    setMessageApi(message);
  }, [message]);

  // 注册 react-router 跳转,让 client.ts 的 401 拦截器能跳登录页(带 replace 不堆历史;已在 /login 则不跳)。
  useEffect(() => {
    setRedirectToLogin(() => {
      if (window.location.pathname !== '/login') navigate('/login', { replace: true });
    });
  }, [navigate]);

  // /login 独立全屏(不套 AppLayout);其余路由经守卫,未登录拦回登录页。
  if (location.pathname === '/login') {
    return <LoginPage />;
  }
  // 顶层兜底边界:连外壳(AppLayout)都崩时也不至于整屏白。resetKey=路径,切页可恢复。
  return (
    <ErrorBoundary resetKey={location.pathname}>
      <RequireAuth>
        <AppLayout>
          <AppRoutes />
        </AppLayout>
      </RequireAuth>
    </ErrorBoundary>
  );
}
