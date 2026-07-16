import { useEffect, useState } from 'react';
import { Button, Result, Spin, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

// Casdoor OIDC 回调页(仅 oidc 模式经 /callback 到达;App.tsx 在守卫之前特判渲染,防 RequireAuth 弹回死循环)。
//
// StrictMode 单飞:dev 下 React.StrictMode 双挂载会让 effect 跑两次,而 code/state 只能兑换一次
// (第二次必抛 "No matching state")。用模块级 in-flight 缓存按 URL(code+state)去重——同一回调只处理一次,
// 两次 effect 共享同一个 promise。
let inflight: { key: string; promise: Promise<string> } | null = null;

function runCallbackOnce(key: string, exchange: () => Promise<string>): Promise<string> {
  if (!inflight || inflight.key !== key) {
    inflight = { key, promise: exchange() };
  }
  return inflight.promise;
}

/** 回调错误人话化:state/code 类错误多为"链接过期或被重放",给统一可行动文案。 */
function humanize(e: unknown): string {
  const msg = e instanceof Error ? e.message : String(e);
  if (/state/i.test(msg)) return '登录会话不匹配(链接过期或页面被重放),请重新登录';
  if (/code|grant/i.test(msg)) return '授权码已失效(可能已使用过),请重新登录';
  return `登录失败:${msg}`;
}

export default function CallbackPage() {
  const { completeSignIn } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    runCallbackOnce(window.location.search, async () => {
      const returnTo = await completeSignIn();
      // 清掉地址栏上的 code/state(防刷新重放、防泄漏到历史记录)。
      window.history.replaceState({}, document.title, returnTo);
      return returnTo;
    }).then(
      (returnTo) => {
        if (active) navigate(returnTo, { replace: true });
      },
      (e: unknown) => {
        if (active) setError(humanize(e));
      },
    );
    return () => {
      active = false;
    };
    // completeSignIn 引用稳定(useCallback);仅在挂载时执行一次。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(160deg, #0d1420 0%, #17233a 100%)',
      }}
    >
      {error ? (
        <Result
          status="error"
          title="登录失败"
          subTitle={error}
          extra={
            <Button type="primary" onClick={() => navigate('/login', { replace: true })}>
              重新登录
            </Button>
          }
          style={{ background: '#fff', borderRadius: 14, padding: 32 }}
        />
      ) : (
        <div style={{ textAlign: 'center' }}>
          <Spin size="large" />
          <Typography.Paragraph style={{ color: '#c8d2e4', marginTop: 16 }}>
            正在完成登录…
          </Typography.Paragraph>
        </div>
      )}
    </div>
  );
}
