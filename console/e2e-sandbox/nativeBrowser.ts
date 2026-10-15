import { chromium } from '@playwright/test';
import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/** Playwright 页面强制 focus emulation；用独占原生 CDP 验证真实标签页 hidden，绝不覆写 visibilityState。 */
export async function nativeBrowser() {
  const profile = await mkdtemp(join(tmpdir(), 'recsys-exposure-browser-'));
  const process = spawn(chromium.executablePath(), [
    '--headless=new',
    '--no-sandbox',
    '--remote-debugging-port=0',
    '--window-size=1280,560',
    '--user-data-dir=' + profile,
    'about:blank',
  ]);
  let socket: WebSocket | undefined;
  try {
    const endpoint = await new Promise<string>((resolve, reject) => {
      let output = '';
      const timeout = setTimeout(() => reject(new Error('独占 Chromium 启动超时')), 10000);
      process.once('error', reject);
      process.stderr.on('data', (chunk) => {
        output = (output + chunk).slice(-8192);
        const match = output.match(/DevTools listening on (ws:\/\/\S+)/);
        if (match) {
          clearTimeout(timeout);
          resolve(match[1]);
        }
      });
    });
    const ws = new WebSocket(endpoint);
    socket = ws;
    await new Promise<void>((resolve, reject) => {
      ws.onopen = () => resolve();
      ws.onerror = reject;
    });
    let sequence = 0;
    const pending = new Map<
      number,
      { resolve: (result: Record<string, unknown>) => void; reject: (error: Error) => void }
    >();
    ws.onmessage = (event) => {
      const message = JSON.parse(String(event.data));
      const call = pending.get(message.id);
      if (call) {
        pending.delete(message.id);
        if (message.error) call.reject(new Error(message.error.message));
        else call.resolve(message.result);
      }
    };
    const send = (method: string, params = {}, sessionId?: string) =>
      new Promise<Record<string, unknown>>((resolve, reject) => {
        const id = ++sequence;
        const timeout = setTimeout(() => {
          pending.delete(id);
          reject(new Error('CDP 超时: ' + method));
        }, 5000);
        pending.set(id, {
          resolve: (result) => {
            clearTimeout(timeout);
            resolve(result);
          },
          reject: (error) => {
            clearTimeout(timeout);
            reject(error);
          },
        });
        ws.send(JSON.stringify({ id, method, params, sessionId }));
      });
    const target = await send('Target.createTarget', { url: 'about:blank' });
    const attached = await send('Target.attachToTarget', {
      targetId: target.targetId,
      flatten: true,
    });
    const sessionId = attached.sessionId as string;
    await send('Page.enable', {}, sessionId);
    return {
      send,
      targetId: target.targetId as string,
      page: (method: string, params = {}) => send(method, params, sessionId),
      evaluate: async <T>(expression: string): Promise<T> => {
        const response = await send(
          'Runtime.evaluate',
          { expression, returnByValue: true, awaitPromise: true },
          sessionId,
        );
        if (response.exceptionDetails) throw new Error('浏览器表达式失败');
        return (response.result as { value: T }).value;
      },
      close: async () => {
        try {
          await send('Browser.close');
        } finally {
          ws.close();
          process.kill();
          await new Promise<void>((resolve) => {
            if (process.exitCode !== null) resolve();
            else process.once('exit', () => resolve());
          });
          await rm(profile, { recursive: true }); // 只清理本函数 mkdtemp 创建的专用浏览器配置。
        }
      },
    };
  } catch (error) {
    socket?.close();
    process.kill();
    await rm(profile, { recursive: true });
    throw error;
  }
}
