import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { Spin } from 'antd';
import RecommendConsole from './pages/online/RecommendConsole';
import SearchConsole from './pages/online/SearchConsole';
import SearchAdsConsole from './pages/online/SearchAdsConsole';
import FeedConsole from './pages/online/FeedConsole';
import QueryParseConsole from './pages/online/QueryParseConsole';
import ExperimentConsole from './pages/online/ExperimentConsole';
import ColdStartInterests from './pages/online/ColdStartInterests';
import AdvertiserList from './pages/adv/AdvertiserList';
import AdvertiserDetail from './pages/adv/AdvertiserDetail';
import AdList from './pages/adv/AdList';
import AdDetail from './pages/adv/AdDetail';
import ReportsIndex from './pages/reports/ReportsIndex';

// ECharts 较重的页面按需加载,避免进入首屏包(初次访问推荐台无需下载 echarts)。
const AdvertiserReport = lazy(() => import('./pages/adv/AdvertiserReport'));
const ReportViewer = lazy(() => import('./pages/reports/ReportViewer'));

export default function AppRoutes() {
  return (
    <Suspense fallback={<Spin style={{ margin: 40 }} />}>
      <Routes>
        <Route path="/" element={<Navigate to="/recommend" replace />} />

        {/* Phase 1 — 在线调试台 */}
        <Route path="/recommend" element={<RecommendConsole />} />
        <Route path="/search" element={<SearchConsole />} />
        <Route path="/search-ads" element={<SearchAdsConsole />} />
        <Route path="/feed" element={<FeedConsole />} />
        <Route path="/query" element={<QueryParseConsole />} />
        <Route path="/experiment" element={<ExperimentConsole />} />
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

        <Route path="*" element={<Navigate to="/recommend" replace />} />
      </Routes>
    </Suspense>
  );
}
