import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp, ConfigProvider, theme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import { GlobalUserProvider } from './hooks/useGlobalUser';
import 'antd/dist/reset.css';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 0, refetchOnWindowFocus: false, staleTime: 5_000 },
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{ algorithm: theme.defaultAlgorithm, token: { colorPrimary: '#2d6cdf' } }}
    >
      <AntdApp>
        <QueryClientProvider client={queryClient}>
          <GlobalUserProvider>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </GlobalUserProvider>
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>
  </React.StrictMode>,
);
