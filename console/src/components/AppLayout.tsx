import { useMemo } from 'react';
import { Layout, Menu, InputNumber, Select, Space, Typography, Grid } from 'antd';
import {
  ThunderboltOutlined,
  SearchOutlined,
  DollarOutlined,
  AppstoreOutlined,
  BulbOutlined,
  ExperimentOutlined,
  HeartOutlined,
  ShopOutlined,
  BarChartOutlined,
} from '@ant-design/icons';
import { Link, useLocation } from 'react-router-dom';
import { useGlobalUser } from '../hooks/useGlobalUser';

const { Header, Sider, Content } = Layout;
const { useBreakpoint } = Grid;

// 三分区导航:在线调试台 / 广告主后台 / 离线报表。路由 key 与 router.tsx 的 path 一一对应。
const menuItems = [
  {
    key: 'grp-online',
    label: '在线调试台',
    type: 'group' as const,
    children: [
      { key: '/recommend', icon: <ThunderboltOutlined />, label: <Link to="/recommend">推荐</Link> },
      { key: '/search', icon: <SearchOutlined />, label: <Link to="/search">搜索</Link> },
      { key: '/search-ads', icon: <DollarOutlined />, label: <Link to="/search-ads">搜索广告</Link> },
      { key: '/feed', icon: <AppstoreOutlined />, label: <Link to="/feed">混排 Feed</Link> },
      { key: '/query', icon: <BulbOutlined />, label: <Link to="/query">Query 理解</Link> },
      { key: '/experiment', icon: <ExperimentOutlined />, label: <Link to="/experiment">实验管理</Link> },
      { key: '/user-interests', icon: <HeartOutlined />, label: <Link to="/user-interests">冷启动兴趣</Link> },
    ],
  },
  {
    key: 'grp-adv',
    label: '广告主后台',
    type: 'group' as const,
    children: [
      { key: '/advertiser', icon: <ShopOutlined />, label: <Link to="/advertiser">广告主 / 广告</Link> },
    ],
  },
  {
    key: 'grp-report',
    label: '离线报表',
    type: 'group' as const,
    children: [
      { key: '/reports', icon: <BarChartOutlined />, label: <Link to="/reports">评测 / 报表</Link> },
    ],
  },
];

const SCENES = ['feed', 'search', 'detail', 'related'];

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  const { userId, scene, setUserId, setScene } = useGlobalUser();
  const screens = useBreakpoint();

  // 高亮当前路由:取一级路径(/advertiser/123/ads 也高亮 /advertiser)。
  const selectedKey = useMemo(() => {
    const p = location.pathname;
    const match = menuItems
      .flatMap((g) => g.children)
      .map((c) => c.key)
      .filter((k) => p === k || p.startsWith(k + '/'))
      .sort((a, b) => b.length - a.length)[0];
    return match ?? '/recommend';
  }, [location.pathname]);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="light" width={220} breakpoint="lg" collapsedWidth={screens.lg ? 220 : 0}>
        <div style={{ padding: '18px 20px', fontSize: 17, fontWeight: 700 }}>
          🎯 recsys 控制台
        </div>
        <Menu mode="inline" selectedKeys={[selectedKey]} items={menuItems} style={{ borderInlineEnd: 0 }} />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            borderBottom: '1px solid #f0f0f0',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            gap: 16,
            paddingInline: 24,
          }}
        >
          <Space size="middle">
            <Space size={6}>
              <Typography.Text type="secondary">userId</Typography.Text>
              <InputNumber min={1} value={userId} onChange={(v) => v && setUserId(v)} style={{ width: 110 }} />
            </Space>
            <Space size={6}>
              <Typography.Text type="secondary">scene</Typography.Text>
              <Select
                value={scene}
                onChange={setScene}
                style={{ width: 120 }}
                options={SCENES.map((s) => ({ value: s, label: s }))}
              />
            </Space>
          </Space>
        </Header>
        <Content style={{ margin: 20 }}>{children}</Content>
      </Layout>
    </Layout>
  );
}
