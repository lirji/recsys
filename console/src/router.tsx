import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './hooks/useAuth';
import { ChartSkeleton } from './components/Skeletons';
import ErrorBoundary from './components/ErrorBoundary';
import ProjectOverview from './pages/project/ProjectOverview';
import User360 from './pages/project/User360';
import Diagnosis from './pages/project/Diagnosis';
import AlertsPanel from './pages/project/Alerts';
import RecommendConsole from './pages/online/RecommendConsole';
import SearchConsole from './pages/online/SearchConsole';
import SearchAdsConsole from './pages/online/SearchAdsConsole';
import FeedConsole from './pages/online/FeedConsole';
import QueryParseConsole from './pages/online/QueryParseConsole';
import RecallLab from './pages/online/RecallLab';
import StrategyLab from './pages/online/StrategyLab';
import ExperimentConsole from './pages/online/ExperimentConsole';
import BucketBoard from './pages/experiment/BucketBoard';
import ColdStartInterests from './pages/online/ColdStartInterests';
import AdvertiserList from './pages/adv/AdvertiserList';
import AdvertiserDetail from './pages/adv/AdvertiserDetail';
import AdList from './pages/adv/AdList';
import AdDetail from './pages/adv/AdDetail';
import ReportsIndex from './pages/reports/ReportsIndex';

// ECharts 较重的页面按需加载,避免进入首屏包(初次访问推荐台无需下载 echarts)。
const AdvertiserReport = lazy(() => import('./pages/adv/AdvertiserReport'));
const ReportViewer = lazy(() => import('./pages/reports/ReportViewer'));

// 路由守卫:未登录(无当前身份)→ 重定向登录页,并记下来源页 from,登录后跳回。
// 用 useAuth().user(响应式)而非直接读 localStorage:退出/切换时能即时触发重定向。
export function RequireAuth({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const location = useLocation();
  if (!user) return <Navigate to="/login" state={{ from: location }} replace />;
  return <>{children}</>;
}

export default function AppRoutes() {
  // 单页崩溃只毁自身:ErrorBoundary 包在 <Routes> 外(侧栏外壳仍在);resetKey=当前路径,
  // 导航到别的页 → 边界自动清错恢复。
  const location = useLocation();
  return (
    <ErrorBoundary resetKey={location.pathname}>
      <Suspense fallback={<div style={{ margin: 20 }}><ChartSkeleton height={360} /></div>}>
        <Routes>
        <Route path="/" element={<Navigate to="/overview" replace />} />

        {/* 项目总览 + 用户360 / 诊断 / 告警 */}
        <Route path="/overview" element={<ProjectOverview />} />
        <Route path="/user360" element={<User360 />} />
        <Route path="/diagnosis" element={<Diagnosis />} />
        <Route path="/alerts" element={<AlertsPanel />} />

        {/* Phase 1 — 在线调试台 */}
        <Route path="/recommend" element={<RecommendConsole />} />
        <Route path="/search" element={<SearchConsole />} />
        <Route path="/search-ads" element={<SearchAdsConsole />} />
        <Route path="/feed" element={<FeedConsole />} />
        <Route path="/query" element={<QueryParseConsole />} />
        <Route path="/recall-lab" element={<RecallLab />} />
        <Route path="/strategy-lab" element={<StrategyLab />} />
        <Route path="/experiment" element={<ExperimentConsole />} />
        <Route path="/bucket-board" element={<BucketBoard />} />
        <Route path="/user-interests" element={<ColdStartInterests />} />

        {/* Phase 2 — 广告主后台 */}
        <Route path="/advertiser" element={<AdvertiserList />} />
        <Route path="/advertiser/ad/:adId" element={<AdDetail />} />
        <Route path="/advertiser/:id" element={<AdvertiserDetail />} />
        <Route path="/advertiser/:id/report" element={<AdvertiserReport />} />
        <Route path="/advertiser/:advId/ads" element={<AdList />} />

        {/* Phase 3 — 离线报表 */}
        <Route path="/reports" element={<ReportsIndex />} />
        <Route path="/reports/:category" element={<ReportViewer />} />

        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Routes>
      </Suspense>
    </ErrorBoundary>
  );
}
