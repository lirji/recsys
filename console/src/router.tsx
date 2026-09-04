import { lazy, Suspense, type ReactNode } from 'react';
import { Spin } from 'antd';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from './hooks/useAuth';
import { ChartSkeleton } from './components/Skeletons';
import ErrorBoundary from './components/ErrorBoundary';
import EmptyState from './components/EmptyState';
import { hasAnyRole } from './api/access';
import type { Role } from './api/auth';

const ProjectOverview = lazy(() => import('./pages/project/ProjectOverview'));
const User360 = lazy(() => import('./pages/project/User360'));
const Diagnosis = lazy(() => import('./pages/project/Diagnosis'));
const AlertsPanel = lazy(() => import('./pages/project/Alerts'));
const OpsConsole = lazy(() => import('./pages/project/OpsConsole'));
const RecommendConsole = lazy(() => import('./pages/online/RecommendConsole'));
const SearchConsole = lazy(() => import('./pages/online/SearchConsole'));
const SearchAdsConsole = lazy(() => import('./pages/online/SearchAdsConsole'));
const FeedConsole = lazy(() => import('./pages/online/FeedConsole'));
const QueryParseConsole = lazy(() => import('./pages/online/QueryParseConsole'));
const RecallLab = lazy(() => import('./pages/online/RecallLab'));
const StrategyLab = lazy(() => import('./pages/online/StrategyLab'));
const ExperimentConsole = lazy(() => import('./pages/online/ExperimentConsole'));
const BucketBoard = lazy(() => import('./pages/experiment/BucketBoard'));
const ColdStartInterests = lazy(() => import('./pages/online/ColdStartInterests'));
const AdvertiserList = lazy(() => import('./pages/adv/AdvertiserList'));
const AdvertiserDetail = lazy(() => import('./pages/adv/AdvertiserDetail'));
const AdList = lazy(() => import('./pages/adv/AdList'));
const AdDetail = lazy(() => import('./pages/adv/AdDetail'));
const AdvertiserReport = lazy(() => import('./pages/adv/AdvertiserReport'));
const ReportsIndex = lazy(() => import('./pages/reports/ReportsIndex'));
const ReportViewer = lazy(() => import('./pages/reports/ReportViewer'));

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth();
  const location = useLocation();
  if (!ready) {
    return (
      <div style={{ minHeight: '60vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Spin size="large" />
      </div>
    );
  }
  if (!user) return <Navigate to="/login" state={{ from: location }} replace />;
  return <>{children}</>;
}

function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { user } = useAuth();
  if (!hasAnyRole(user?.roles, roles)) {
    return (
      <EmptyState
        title="没有访问权限"
        description="当前身份看不到该页。可切换演示账号,或联系管理员开通对应角色。"
      />
    );
  }
  return <>{children}</>;
}

export default function AppRoutes() {
  const location = useLocation();
  return (
    <ErrorBoundary resetKey={location.pathname}>
      <Suspense fallback={<div style={{ margin: 20 }}><ChartSkeleton height={360} /></div>}>
        <Routes>
        <Route path="/" element={<Navigate to="/overview" replace />} />

        <Route path="/overview" element={<ProjectOverview />} />
        <Route path="/user360" element={<User360 />} />
        <Route path="/diagnosis" element={<RequireRole roles={['ADMIN']}><Diagnosis /></RequireRole>} />
        <Route path="/alerts" element={<RequireRole roles={['ADMIN']}><AlertsPanel /></RequireRole>} />
        <Route path="/ops" element={<RequireRole roles={['ADMIN']}><OpsConsole /></RequireRole>} />

        <Route path="/recommend" element={<RecommendConsole />} />
        <Route path="/search" element={<SearchConsole />} />
        <Route path="/search-ads" element={<SearchAdsConsole />} />
        <Route path="/feed" element={<FeedConsole />} />
        <Route path="/query" element={<QueryParseConsole />} />
        <Route path="/recall-lab" element={<RecallLab />} />
        <Route path="/strategy-lab" element={<StrategyLab />} />
        <Route path="/experiment" element={<RequireRole roles={['ADMIN']}><ExperimentConsole /></RequireRole>} />
        <Route path="/bucket-board" element={<RequireRole roles={['ADMIN']}><BucketBoard /></RequireRole>} />
        <Route path="/user-interests" element={<RequireRole roles={['ADMIN']}><ColdStartInterests /></RequireRole>} />

        <Route path="/advertiser" element={<RequireRole roles={['ADMIN', 'ADVERTISER']}><AdvertiserList /></RequireRole>} />
        <Route path="/advertiser/ad/:adId" element={<RequireRole roles={['ADMIN', 'ADVERTISER']}><AdDetail /></RequireRole>} />
        <Route path="/advertiser/:id" element={<RequireRole roles={['ADMIN', 'ADVERTISER']}><AdvertiserDetail /></RequireRole>} />
        <Route path="/advertiser/:id/report" element={<RequireRole roles={['ADMIN', 'ADVERTISER']}><AdvertiserReport /></RequireRole>} />
        <Route path="/advertiser/:advId/ads" element={<RequireRole roles={['ADMIN', 'ADVERTISER']}><AdList /></RequireRole>} />

        <Route path="/reports" element={<RequireRole roles={['ADMIN', 'ADVERTISER']}><ReportsIndex /></RequireRole>} />
        <Route path="/reports/:category" element={<RequireRole roles={['ADMIN', 'ADVERTISER']}><ReportViewer /></RequireRole>} />

        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Routes>
      </Suspense>
    </ErrorBoundary>
  );
}
