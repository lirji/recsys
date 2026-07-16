import { useState, type KeyboardEvent, type ReactNode } from 'react';
import { App, Avatar, Button, Divider, Form, Grid, Input, Space, Tag, Typography } from 'antd';
import {
  DollarOutlined,
  ExperimentOutlined,
  LoadingOutlined,
  LockOutlined,
  LoginOutlined,
  RightOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { DEMO_USERS } from '../api/auth';
import { toApiError } from '../api/client';
import { DEMO_USER_META, ROLE_META } from '../components/AppLayout';
import { AUTH_MODE } from '../config/auth';

const { useBreakpoint } = Grid;

interface LoginForm {
  username: string;
  password: string;
}

// 品牌区能力小点(与线上功能一致)。
const FEATURES: { icon: ReactNode; title: string; desc: string }[] = [
  { icon: <ThunderboltOutlined />, title: '召回 → 排序 → 重排', desc: '可视化推荐漏斗全链路' },
  { icon: <DollarOutlined />, title: '搜索广告竞价', desc: 'eCPM 竞价 · GSP 计费 · oCPC 出价' },
  { icon: <ExperimentOutlined />, title: '分层 A/B 实验', desc: 'recall × rank × rerank 实时放量' },
];

// 组件内联作用域样式:统一 .lpa-* 前缀,绝不与其它登录变体冲突,也不动全局 index.css。
const LPA_CSS = `
.lpa-root {
  position: relative;
  min-height: 100vh;
  display: flex;
  overflow: hidden;
  background: linear-gradient(160deg, #eaf1ff 0%, #f2f0ff 46%, #e9fbff 100%);
  font-feature-settings: 'tnum';
}
/* ---- 极光背景:多枚模糊色团缓慢漂浮 ---- */
.lpa-aurora { position: absolute; inset: 0; z-index: 0; overflow: hidden; }
.lpa-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(72px);
  will-change: transform;
}
.lpa-blob-1 {
  width: 540px; height: 540px; top: -12%; left: -8%;
  background: radial-gradient(circle at center, rgba(45,108,223,0.82), rgba(45,108,223,0) 68%);
  animation: lpa-float-a 24s ease-in-out infinite alternate;
}
.lpa-blob-2 {
  width: 480px; height: 480px; top: 4%; right: -8%;
  background: radial-gradient(circle at center, rgba(34,211,238,0.72), rgba(34,211,238,0) 68%);
  animation: lpa-float-b 29s ease-in-out infinite alternate;
}
.lpa-blob-3 {
  width: 600px; height: 600px; bottom: -20%; left: 16%;
  background: radial-gradient(circle at center, rgba(99,102,241,0.7), rgba(99,102,241,0) 68%);
  animation: lpa-float-c 33s ease-in-out infinite alternate;
}
.lpa-blob-4 {
  width: 440px; height: 440px; bottom: -8%; right: 8%;
  background: radial-gradient(circle at center, rgba(139,92,246,0.62), rgba(139,92,246,0) 68%);
  animation: lpa-float-a 27s ease-in-out infinite alternate-reverse;
}
.lpa-blob-5 {
  width: 400px; height: 400px; top: 34%; left: 40%;
  background: radial-gradient(circle at center, rgba(79,124,255,0.5), rgba(79,124,255,0) 70%);
  animation: lpa-float-b 31s ease-in-out infinite alternate;
}
@keyframes lpa-float-a {
  0%   { transform: translate(0, 0) scale(1); }
  50%  { transform: translate(7%, -5%) scale(1.12); }
  100% { transform: translate(-5%, 6%) scale(0.96); }
}
@keyframes lpa-float-b {
  0%   { transform: translate(0, 0) scale(1); }
  50%  { transform: translate(-8%, 6%) scale(1.1); }
  100% { transform: translate(5%, -6%) scale(0.94); }
}
@keyframes lpa-float-c {
  0%   { transform: translate(0, 0) scale(1); }
  50%  { transform: translate(6%, 7%) scale(1.08); }
  100% { transform: translate(-7%, -5%) scale(1); }
}

/* ---- 舞台与玻璃卡片 ---- */
.lpa-stage {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 28px 20px;
}
.lpa-card {
  display: flex;
  width: 100%;
  border-radius: 22px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.72);
  -webkit-backdrop-filter: blur(22px) saturate(165%);
  backdrop-filter: blur(22px) saturate(165%);
  border: 1px solid rgba(255, 255, 255, 0.65);
  box-shadow:
    0 26px 70px rgba(26, 54, 110, 0.22),
    0 2px 8px rgba(26, 54, 110, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.75);
  animation: lpa-rise 0.6s cubic-bezier(0.22, 1, 0.36, 1) both;
}
@keyframes lpa-rise {
  from { opacity: 0; transform: translateY(16px) scale(0.985); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
}

/* ---- 品牌区(桌面) ---- */
.lpa-brand {
  position: relative;
  flex: 0 0 45%;
  padding: 52px 46px;
  color: #fff;
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: linear-gradient(150deg, rgba(45,108,223,0.94) 0%, rgba(79,70,229,0.9) 52%, rgba(6,182,212,0.86) 100%);
  overflow: hidden;
}
.lpa-brand::before {
  content: '';
  position: absolute;
  top: -30%; left: -20%;
  width: 70%; height: 70%;
  background: radial-gradient(circle at center, rgba(255,255,255,0.35), rgba(255,255,255,0) 70%);
  pointer-events: none;
}
.lpa-brand-logo { font-size: 32px; font-weight: 800; letter-spacing: 0.5px; }
.lpa-brand-tag { font-size: 15px; opacity: 0.92; margin-top: 12px; line-height: 1.6; }
.lpa-feat-list { margin-top: 40px; display: flex; flex-direction: column; gap: 20px; }
.lpa-feat { display: flex; gap: 14px; align-items: flex-start; }
.lpa-feat-ico {
  width: 40px; height: 40px; border-radius: 11px;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid rgba(255, 255, 255, 0.28);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 18px; flex: 0 0 auto;
}
.lpa-feat-title { font-weight: 600; font-size: 15px; }
.lpa-feat-desc { opacity: 0.84; font-size: 13px; margin-top: 2px; }
.lpa-brand-foot { margin-top: 44px; font-size: 12px; opacity: 0.72; }

/* ---- 表单区 ---- */
.lpa-form-panel {
  flex: 1;
  min-width: 0;
  padding: 42px 42px 34px;
  display: flex;
  flex-direction: column;
}
.lpa-compact-head { text-align: center; margin-bottom: 26px; }
.lpa-logo {
  font-size: 25px; font-weight: 800; letter-spacing: 0.4px;
  background: linear-gradient(120deg, #2d6cdf, #6366f1 55%, #06b6d4);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}

.lpa-form-panel .ant-input-affix-wrapper {
  background: rgba(255, 255, 255, 0.6);
  border-color: rgba(45, 108, 223, 0.16);
  border-radius: 11px;
  transition: border-color 0.2s ease, background 0.2s ease, box-shadow 0.2s ease;
}
.lpa-form-panel .ant-input-affix-wrapper:hover {
  border-color: rgba(45, 108, 223, 0.45);
  background: rgba(255, 255, 255, 0.82);
}
.lpa-form-panel .ant-input-affix-wrapper-focused {
  border-color: #2d6cdf;
  background: #fff;
  box-shadow: 0 0 0 3px rgba(45, 108, 223, 0.16);
}
.lpa-form-panel .ant-input-affix-wrapper .ant-input { background: transparent; }

.lpa-btn.ant-btn-primary {
  height: 46px;
  border: none;
  font-weight: 600;
  letter-spacing: 2px;
  background-image: linear-gradient(120deg, #2d6cdf 0%, #4f6ff0 46%, #21a9e0 100%);
  background-size: 170% 170%;
  background-position: 0% 50%;
  box-shadow: 0 10px 24px rgba(45, 108, 223, 0.32);
  transition: background-position 0.5s ease, box-shadow 0.25s ease, transform 0.15s ease;
}
.lpa-btn.ant-btn-primary:not(:disabled):hover {
  background-position: 100% 50%;
  box-shadow: 0 14px 32px rgba(45, 108, 223, 0.44);
  transform: translateY(-1px);
}
.lpa-btn.ant-btn-primary:not(:disabled):active { transform: translateY(0); }

.lpa-demo-list { display: flex; flex-direction: column; gap: 9px; }
.lpa-demo-row {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 9px 12px;
  border: 1px solid rgba(45, 108, 223, 0.13);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.46);
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease, background 0.18s ease, box-shadow 0.18s ease;
}
.lpa-demo-row:hover {
  border-color: rgba(45, 108, 223, 0.5);
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 8px 20px rgba(45, 108, 223, 0.16);
  transform: translateY(-1px);
}
.lpa-demo-row:focus-visible {
  outline: none;
  border-color: #2d6cdf;
  box-shadow: 0 0 0 3px rgba(45, 108, 223, 0.18);
}
.lpa-demo-row .lpa-arrow { transition: transform 0.18s ease, color 0.18s ease; }
.lpa-demo-row:hover .lpa-arrow { transform: translateX(3px); color: #2d6cdf; }
.lpa-demo-row--busy { cursor: not-allowed; }
.lpa-demo-row--busy:hover {
  transform: none;
  box-shadow: none;
  border-color: rgba(45, 108, 223, 0.13);
  background: rgba(255, 255, 255, 0.46);
}
.lpa-demo-row--busy:hover .lpa-arrow { transform: none; color: rgba(0, 0, 0, 0.25); }

.lpa-foot { font-size: 12px; color: rgba(30, 50, 90, 0.5); text-align: center; }

@media (max-width: 420px) {
  .lpa-form-panel { padding: 30px 22px 26px; }
}

@media (prefers-reduced-motion: reduce) {
  .lpa-blob { animation: none !important; }
  .lpa-card { animation: none !important; }
  .lpa-demo-row,
  .lpa-demo-row .lpa-arrow,
  .lpa-btn.ant-btn-primary,
  .lpa-form-panel .ant-input-affix-wrapper { transition: none !important; }
}
`;

export default function LoginPage() {
  const { signIn, signInOidc } = useAuth();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const location = useLocation();
  const screens = useBreakpoint();

  const [loading, setLoading] = useState(false); // 表单登录中
  const [pending, setPending] = useState<string | null>(null); // 正在一键登录的 demo 用户
  const [redirecting, setRedirecting] = useState(false); // oidc:正在跳 Casdoor

  // 登录成功回来源页(被守卫拦截时记下的 from,完整 location),否则默认 /overview。
  // 读取侧拼全 pathname+search+hash(深链带参精确恢复;oidc 下经 Casdoor state 往返)。
  const state = location.state as {
    from?: { pathname?: string; search?: string; hash?: string };
  } | null;
  const from = state?.from?.pathname
    ? `${state.from.pathname}${state.from.search ?? ''}${state.from.hash ?? ''}`
    : '/overview';
  const busy = loading || pending !== null || redirecting;
  const compact = !screens.md; // 窄屏:收起品牌区,卡片自带紧凑品牌头

  // oidc:整页跳 Casdoor 授权页;跳转前置 loading 态(随即离开本页,失败才复位)。
  const onOidcLogin = async () => {
    if (busy) return;
    setRedirecting(true);
    try {
      await signInOidc(from);
    } catch (e) {
      setRedirecting(false);
      message.error(`无法跳转统一登录:${toApiError(e).message}(请确认 Casdoor 可达)`);
    }
  };

  const doLogin = async (username: string, password: string) => {
    try {
      await signIn(username, password);
      message.success(`欢迎回来,${username}`);
      navigate(from, { replace: true });
    } catch (e) {
      const info = toApiError(e);
      message.error(
        info.status === 401 || info.status === 403
          ? '用户名或密码错误'
          : `登录失败:${info.message}`,
      );
      throw e; // 让调用方 finally 复位 loading
    }
  };

  const onFinish = async (v: LoginForm) => {
    setLoading(true);
    try {
      await doLogin(v.username, v.password);
    } catch {
      /* 已提示 */
    } finally {
      setLoading(false);
    }
  };

  const onDemo = async (username: string) => {
    if (busy) return;
    setPending(username);
    try {
      await doLogin(username, username); // 密码 = 用户名
    } catch {
      /* 已提示 */
    } finally {
      setPending(null);
    }
  };

  const onDemoKey = (e: KeyboardEvent<HTMLDivElement>, username: string) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      onDemo(username);
    }
  };

  const brand = (
    <aside className="lpa-brand">
      <div className="lpa-brand-logo">🎯 recsys 控制台</div>
      <div className="lpa-brand-tag">搜索 · 推荐 · 广告 一体化在线调试台</div>
      <div className="lpa-feat-list">
        {FEATURES.map((f) => (
          <div className="lpa-feat" key={f.title}>
            <span className="lpa-feat-ico">{f.icon}</span>
            <div>
              <div className="lpa-feat-title">{f.title}</div>
              <div className="lpa-feat-desc">{f.desc}</div>
            </div>
          </div>
        ))}
      </div>
      <div className="lpa-brand-foot">在线 · 离线一体 · 毫秒级链路可视化</div>
    </aside>
  );

  // oidc 单按钮态:演示表单/demo 账号全部不渲染,单一主行动按钮跳统一登录。
  const oidcPanel = (
    <section className="lpa-form-panel">
      {compact && (
        <div className="lpa-compact-head">
          <div className="lpa-logo">🎯 recsys 控制台</div>
          <Typography.Text type="secondary">搜索 · 推荐 · 广告 一体化在线调试台</Typography.Text>
        </div>
      )}

      <Typography.Title level={4} style={{ marginTop: 0, marginBottom: 2 }}>
        统一身份登录
      </Typography.Title>
      <Typography.Text type="secondary">通过企业 Casdoor 账号登录 · 授权后自动跳回</Typography.Text>

      <Button
        className="lpa-btn"
        type="primary"
        size="large"
        block
        loading={redirecting}
        icon={<LoginOutlined />}
        onClick={onOidcLogin}
        style={{ marginTop: 24 }}
      >
        {redirecting ? '正在跳转 Casdoor…' : '使用统一身份登录'}
      </Button>

      <Typography.Text type="secondary" style={{ fontSize: 12, marginTop: 14, display: 'block' }}>
        将跳转到统一登录平台完成认证;登录状态仅保存在当前标签页。
      </Typography.Text>
    </section>
  );

  const formPanel = (
    <section className="lpa-form-panel">
      {compact && (
        <div className="lpa-compact-head">
          <div className="lpa-logo">🎯 recsys 控制台</div>
          <Typography.Text type="secondary">搜索 · 推荐 · 广告 一体化在线调试台</Typography.Text>
        </div>
      )}

      <Typography.Title level={4} style={{ marginTop: 0, marginBottom: 2 }}>
        登录
      </Typography.Title>
      <Typography.Text type="secondary">内部调试台 · 使用演示账号登录</Typography.Text>

      <Form<LoginForm>
        layout="vertical"
        requiredMark={false}
        initialValues={{ username: 'admin', password: 'admin' }}
        onFinish={onFinish}
        disabled={busy}
        style={{ marginTop: 20 }}
      >
        <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
          <Input
            size="large"
            prefix={<UserOutlined style={{ color: '#9aa4b8' }} />}
            placeholder="用户名"
            autoComplete="username"
          />
        </Form.Item>
        <Form.Item
          name="password"
          rules={[{ required: true, message: '请输入密码' }]}
          style={{ marginBottom: 16 }}
        >
          <Input.Password
            size="large"
            prefix={<LockOutlined style={{ color: '#9aa4b8' }} />}
            placeholder="密码"
            autoComplete="current-password"
          />
        </Form.Item>
        <Form.Item style={{ marginBottom: 0 }}>
          <Button
            className="lpa-btn"
            type="primary"
            htmlType="submit"
            size="large"
            block
            loading={loading}
            icon={<LoginOutlined />}
          >
            登 录
          </Button>
        </Form.Item>
      </Form>

      <Divider plain style={{ color: '#9aa4b8', fontSize: 12, marginBlock: 20 }}>
        或用演示账号快速登录
      </Divider>

      <div className="lpa-demo-list">
        {DEMO_USERS.map((uname) => {
          const um = DEMO_USER_META[uname];
          const rm = ROLE_META[um.role];
          const isPending = pending === uname;
          return (
            <div
              key={uname}
              role="button"
              tabIndex={busy ? -1 : 0}
              aria-disabled={busy}
              aria-label={`使用演示账号 ${uname}（${rm.label}）登录`}
              className={`lpa-demo-row${busy ? ' lpa-demo-row--busy' : ''}`}
              onClick={() => onDemo(uname)}
              onKeyDown={(e) => onDemoKey(e, uname)}
              style={{ opacity: pending && !isPending ? 0.45 : 1 }}
            >
              <Avatar
                size="small"
                style={{ background: rm.avatarBg, flex: '0 0 auto' }}
                icon={isPending ? <LoadingOutlined /> : rm.icon}
              />
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
              <RightOutlined
                className="lpa-arrow"
                style={{ color: '#9aa4b8', fontSize: 12, flex: '0 0 auto' }}
              />
            </div>
          );
        })}
      </div>

      <Typography.Text type="secondary" style={{ fontSize: 12, marginTop: 14, display: 'block' }}>
        点击任意演示账号即可一键登录(密码 = 用户名)。
      </Typography.Text>
    </section>
  );

  return (
    <div className="lpa-root">
      <style>{LPA_CSS}</style>
      <div className="lpa-aurora" aria-hidden="true">
        <span className="lpa-blob lpa-blob-1" />
        <span className="lpa-blob lpa-blob-2" />
        <span className="lpa-blob lpa-blob-3" />
        <span className="lpa-blob lpa-blob-4" />
        <span className="lpa-blob lpa-blob-5" />
      </div>

      <div className="lpa-stage">
        <div className="lpa-card" style={{ maxWidth: compact ? 420 : 900 }}>
          {!compact && brand}
          {AUTH_MODE === 'oidc' ? oidcPanel : formPanel}
        </div>
        <div className="lpa-foot">内部调试台 · 数据仅供演示</div>
      </div>
    </div>
  );
}
