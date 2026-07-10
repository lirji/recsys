import { useMemo, useState, type ReactNode } from 'react';
import {
  App,
  Avatar,
  Button,
  Divider,
  Dropdown,
  Grid,
  InputNumber,
  Layout,
  Menu,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import {
  AppstoreOutlined,
  BarChartOutlined,
  BulbOutlined,
  CheckOutlined,
  ClusterOutlined,
  DollarOutlined,
  DownOutlined,
  ExperimentOutlined,
  HeartOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  ShopOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useGlobalUser } from '../hooks/useGlobalUser';
import { useAuth } from '../hooks/useAuth';
import { DEMO_USERS, getCurrentUser, type Role } from '../api/auth';
import { toApiError } from '../api/client';
import { BRAND, SURFACE, rgba } from '../theme/tokens';

const { Header, Sider, Content } = Layout;
const { useBreakpoint } = Grid;

// 外壳作用域样式(.shell-*):侧边栏渐变字 brand + 顶栏吸顶阴影。AppLayout 单实例,故内联 <style> 只注入一次。
const SHELL_CSS = `
.shell-brand{padding:16px 20px 14px;display:flex;align-items:center;gap:10px;border-bottom:1px solid #f0f0f0;}
.shell-logo-badge{
  width:28px;height:28px;border-radius:9px;flex:0 0 auto;
  display:inline-flex;align-items:center;justify-content:center;font-size:15px;
  background:linear-gradient(135deg, ${rgba(BRAND, 0.16)}, rgba(6,182,212,0.16));
  border:1px solid ${rgba(BRAND, 0.28)};
}
.shell-logo{
  font-size:17px;font-weight:800;letter-spacing:.3px;
  background:linear-gradient(120deg, ${BRAND}, #6366f1, #06b6d4);
  -webkit-background-clip:text;background-clip:text;
  -webkit-text-fill-color:transparent;color:transparent;
}
.shell-brand--collapsed{justify-content:center;padding-inline:0;}
.shell-header{
  position:sticky;top:0;z-index:10;
  box-shadow:0 1px 8px -3px rgba(20,40,90,0.14);
}
.shell-fold{
  font-size:17px;color:#5b6b86;
}
.shell-fold:hover{color:${BRAND};}
/* 选中项左侧强调条,给菜单更多色彩识别度 */
.shell-sider .ant-menu-item{position:relative;}
.shell-sider .ant-menu-item-selected::before{
  content:'';position:absolute;left:0;top:6px;bottom:6px;width:3px;
  border-radius:0 3px 3px 0;background:${BRAND};
}
/* 收起成图标栏时:隐藏分组标题,让它是干净的图标轨 */
.shell-sider .ant-menu-inline-collapsed .ant-menu-item-group-title{display:none;}
`;

// 三分区导航:在线调试台 / 广告主后台 / 离线报表。路由 key 与 router.tsx 的 path 一一对应。
// label 用纯文本(非 <Link>):导航走 Menu onClick→navigate(key),这样收起成图标栏时点图标也能跳转,
// 且收起态的悬浮 tooltip 直接用 label 文本,干净。
const menuItems = [
  {
    key: 'grp-project',
    label: '项目',
    type: 'group' as const,
    children: [{ key: '/overview', icon: <ClusterOutlined />, label: '系统总览' }],
  },
  {
    key: 'grp-online',
    label: '在线调试台',
    type: 'group' as const,
    children: [
      { key: '/recommend', icon: <ThunderboltOutlined />, label: '推荐' },
      { key: '/search', icon: <SearchOutlined />, label: '搜索' },
      { key: '/search-ads', icon: <DollarOutlined />, label: '搜索广告' },
      { key: '/feed', icon: <AppstoreOutlined />, label: '混排 Feed' },
      { key: '/query', icon: <BulbOutlined />, label: 'Query 理解' },
      { key: '/experiment', icon: <ExperimentOutlined />, label: '实验管理' },
      { key: '/user-interests', icon: <HeartOutlined />, label: '冷启动兴趣' },
    ],
  },
  {
    key: 'grp-adv',
    label: '广告主后台',
    type: 'group' as const,
    children: [{ key: '/advertiser', icon: <ShopOutlined />, label: '广告主 / 广告' }],
  },
  {
    key: 'grp-report',
    label: '离线报表',
    type: 'group' as const,
    children: [{ key: '/reports', icon: <BarChartOutlined />, label: '评测 / 报表' }],
  },
];

const SCENES = ['feed', 'search', 'detail', 'related'];

// 角色视觉:头像图标 + 头像底色 + Tag 语义色 + 中文标签。与全站语义色基调一致。登录页复用同一套配色。
export const ROLE_META: Record<Role, { icon: ReactNode; avatarBg: string; tagColor: string; label: string }> = {
  ADMIN: { icon: <SafetyCertificateOutlined />, avatarBg: '#2f54eb', tagColor: 'geekblue', label: '管理员' },
  ADVERTISER: { icon: <ShopOutlined />, avatarBg: '#fa8c16', tagColor: 'orange', label: '广告主' },
  USER: { icon: <UserOutlined />, avatarBg: '#13c2c2', tagColor: 'cyan', label: '用户' },
};

// 演示用户 → 主角色 + 一行权限说明(下拉 / 登录页里展示)。
export const DEMO_USER_META: Record<(typeof DEMO_USERS)[number], { role: Role; desc: string }> = {
  admin: { role: 'ADMIN', desc: '全部权限(实验 / 广告主 / 只读)' },
  advertiser: { role: 'ADVERTISER', desc: '广告主后台 + 只读推荐 / 搜索' },
  user: { role: 'USER', desc: '仅在线只读(打管理页会 403)' },
};

/**
 * 身份切换器:头像 chip 触发 + Dropdown 自定义面板。切 admin/advertiser/user 演示 RBAC。
 * 放在 Header 右侧现有 userId/scene 之后,不干扰调试用户(userId 是「被推荐的用户」,这里是「登录身份」)。
 */
function IdentitySwitcher() {
  const { user, switching, switchUser, logout } = useAuth();
  const { message } = App.useApp();
  const screens = useBreakpoint();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);

  const currentRole: Role = user?.roles?.[0] ?? 'USER';
  const meta = ROLE_META[currentRole];
  const compact = !screens.md; // 窄屏:只保留头像 + 角色 Tag

  const handleSwitch = async (username: string) => {
    if (switching) return; // 切换中禁止重复触发
    setOpen(false);
    try {
      await switchUser(username);
      const u = getCurrentUser();
      message.success(`已切换为 ${u?.username ?? username} · ${(u?.roles ?? []).join(' / ')}`);
    } catch (e) {
      message.error(`切换失败:${toApiError(e).message}`);
    }
  };

  const handleLogout = () => {
    setOpen(false);
    logout(); // 清身份态 → 路由守卫会拦回登录页
    navigate('/login', { replace: true });
  };

  // 行 hover 反馈(避免额外 CSS):按是否当前身份给不同的静息/悬停背景。
  const rowBg = (active: boolean, hover: boolean) =>
    active
      ? hover
        ? 'rgba(45,108,223,0.14)'
        : 'rgba(45,108,223,0.08)'
      : hover
        ? '#f5f5f5'
        : 'transparent';

  const panel = (
    <div
      style={{
        width: 300,
        background: '#fff',
        borderRadius: 10,
        boxShadow: '0 6px 24px rgba(0,0,0,0.12)',
        border: '1px solid #f0f0f0',
        padding: 8,
      }}
    >
      <div style={{ padding: '6px 10px 8px' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          切换调试身份 · 演示 RBAC
        </Typography.Text>
      </div>
      {DEMO_USERS.map((uname) => {
        const um = DEMO_USER_META[uname];
        const rm = ROLE_META[um.role];
        const active = user?.username === uname;
        return (
          <div
            key={uname}
            role="button"
            onClick={() => handleSwitch(uname)}
            onMouseEnter={(e) => {
              e.currentTarget.style.background = rowBg(active, true);
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.background = rowBg(active, false);
            }}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              padding: '8px 10px',
              borderRadius: 8,
              cursor: switching ? 'not-allowed' : 'pointer',
              background: rowBg(active, false),
              transition: 'background 0.15s',
            }}
          >
            <Avatar size="small" style={{ background: rm.avatarBg, flex: '0 0 auto' }} icon={rm.icon} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <Space size={6} align="center">
                <Typography.Text strong>{uname}</Typography.Text>
                <Tag color={rm.tagColor} style={{ marginInlineEnd: 0 }}>
                  {rm.label}
                </Tag>
              </Space>
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {um.desc}
                </Typography.Text>
              </div>
            </div>
            {active && <CheckOutlined style={{ color: BRAND, flex: '0 0 auto' }} />}
          </div>
        );
      })}
      <Divider style={{ margin: '6px 4px' }} />
      <div
        role="button"
        onClick={handleLogout}
        onMouseEnter={(e) => {
          e.currentTarget.style.background = '#fff1f0';
        }}
        onMouseLeave={(e) => {
          e.currentTarget.style.background = 'transparent';
        }}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '8px 10px',
          borderRadius: 8,
          cursor: 'pointer',
          color: '#cf1322',
          background: 'transparent',
          transition: 'background 0.15s',
        }}
      >
        <LogoutOutlined style={{ fontSize: 15 }} />
        <Typography.Text style={{ color: '#cf1322' }}>退出登录</Typography.Text>
      </div>
    </div>
  );

  return (
    <Dropdown
      open={open}
      onOpenChange={setOpen}
      trigger={['click']}
      placement="bottomRight"
      dropdownRender={() => panel}
    >
      <div
        role="button"
        onMouseEnter={(e) => {
          e.currentTarget.style.background = '#fafafa';
          e.currentTarget.style.borderColor = '#e6e6e6';
        }}
        onMouseLeave={(e) => {
          e.currentTarget.style.background = 'transparent';
          e.currentTarget.style.borderColor = 'transparent';
        }}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          height: 36,
          paddingInline: 10,
          borderRadius: 8,
          border: '1px solid transparent',
          cursor: 'pointer',
          transition: 'background 0.2s, border-color 0.2s',
          userSelect: 'none',
        }}
      >
        {switching ? (
          <Spin size="small" />
        ) : (
          <Avatar size="small" style={{ background: meta.avatarBg, flex: '0 0 auto' }} icon={meta.icon} />
        )}
        {!compact && (
          <Typography.Text strong style={{ maxWidth: 100 }} ellipsis>
            {user?.username ?? '连接中'}
          </Typography.Text>
        )}
        {user && (
          <Tag color={meta.tagColor} style={{ marginInlineEnd: 0 }}>
            {meta.label}
          </Tag>
        )}
        <DownOutlined style={{ fontSize: 10, color: '#8c8c8c' }} />
      </div>
    </Dropdown>
  );
}

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  const navigate = useNavigate();
  const { userId, scene, setUserId, setScene } = useGlobalUser();
  const screens = useBreakpoint();
  // 侧边栏收起状态,持久化到 localStorage,刷新后保持。
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem('sider-collapsed') === '1');
  const toggleCollapsed = () => {
    setCollapsed((c) => {
      const next = !c;
      localStorage.setItem('sider-collapsed', next ? '1' : '0');
      return next;
    });
  };

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
      <Sider
        className="shell-sider"
        theme="light"
        width={220}
        breakpoint="lg"
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        trigger={null}
        collapsedWidth={screens.lg ? 72 : 0}
        style={{ background: SURFACE.sider, borderRight: `1px solid ${SURFACE.cardBorder}` }}
      >
        <style>{SHELL_CSS}</style>
        <div className={`shell-brand${collapsed ? ' shell-brand--collapsed' : ''}`}>
          <span className="shell-logo-badge" aria-hidden>
            🎯
          </span>
          {!collapsed && <span className="shell-logo">recsys 控制台</span>}
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
          style={{ borderInlineEnd: 0, background: 'transparent' }}
        />
      </Sider>
      <Layout style={{ background: SURFACE.page }}>
        <Header
          className="shell-header"
          style={{
            background: '#fff',
            borderBottom: '1px solid #f0f0f0',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 16,
            paddingInline: 24,
          }}
        >
          <Button
            type="text"
            className="shell-fold"
            aria-label={collapsed ? '展开菜单' : '收起菜单'}
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={toggleCollapsed}
          />
          <Space size="middle" align="center">
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
            <Divider type="vertical" style={{ height: 24, marginInline: 0 }} />
            <IdentitySwitcher />
          </Space>
        </Header>
        <Content style={{ margin: 20 }}>{children}</Content>
      </Layout>
    </Layout>
  );
}
