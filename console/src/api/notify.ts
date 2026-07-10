import type { MessageInstance } from 'antd/es/message/interface';

// client.ts / auth.ts 等「非 React」模块想弹 AntD 全局提示,但 AntD 5 的 message 需要 <App> 上下文(静态 message.xxx 无主题)。
// 方案:顶层组件(App.tsx)用 App.useApp() 拿到 message 实例注册到这个模块级 holder,非组件代码经 notifyError 调用。
let messageApi: MessageInstance | null = null;

/** 顶层组件挂载时注入 App.useApp().message 实例。 */
export function setMessageApi(api: MessageInstance): void {
  messageApi = api;
}

/**
 * 全局错误提示。带 key → 同类提示去重(避免一个页面多个请求同时 403 时刷屏)。
 * message 未就绪(注册前的极早期请求)则静默,不抛错。
 */
export function notifyError(content: string, key?: string): void {
  messageApi?.error({ content, key });
}
