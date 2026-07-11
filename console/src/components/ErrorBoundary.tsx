import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button, Result } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';

interface Props {
  children: ReactNode;
  /**
   * 变化时自动重置边界(通常传 location.pathname)。
   * 某页崩了 → 用户从侧栏导航到别的页 → resetKey 变化 → 边界自恢复,渲染新页面。
   */
  resetKey?: unknown;
}

interface State {
  error: Error | null;
}

/**
 * 渲染错误边界:兜住子树抛出的渲染期异常,避免单页 / 单图报错白屏整站。
 * 放在 router.tsx 的 <Routes> 外层(保住侧栏外壳),另在 App.tsx 顶层再包一层兜底。
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidUpdate(prev: Props) {
    // 处于错误态且 resetKey 变了(一般是路由切换)→ 自动清错,让导航「就地愈合」。
    if (this.state.error && prev.resetKey !== this.props.resetKey) {
      this.setState({ error: null });
    }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // 保留栈到控制台便于排查(生产同样打点,后续可接上报)。
    console.error('[ErrorBoundary] 未捕获的渲染错误:', error, info.componentStack);
  }

  private handleReset = () => this.setState({ error: null });

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;

    return (
      <div role="alert" style={{ padding: 24 }}>
        <Result
          status="error"
          title="页面渲染出错了"
          subTitle={
            error.message ||
            '渲染时发生未捕获的异常。可点「重试」重新加载本页,或从左侧导航切换到其他页面(会自动恢复)。'
          }
          extra={
            <Button type="primary" icon={<ReloadOutlined />} onClick={this.handleReset}>
              重试
            </Button>
          }
        >
          {error.stack && (
            <details open={import.meta.env.DEV} style={{ textAlign: 'left' }}>
              <summary style={{ cursor: 'pointer', userSelect: 'none', color: '#8c8c8c', fontSize: 12 }}>
                错误堆栈{import.meta.env.DEV ? '(开发环境)' : ''}
              </summary>
              <pre className="json-block" style={{ marginTop: 8, maxHeight: 320 }}>
                {error.stack}
              </pre>
            </details>
          )}
        </Result>
      </div>
    );
  }
}
