import { useEffect, useState } from 'react';

// 自定义事件名:让「非同组件」的触发点(顶栏 ⌘K 按钮)也能打开唯一挂载的命令面板,免 Context / 免 prop 透传。
const OPEN_EVENT = 'cmdk:open';

/** 供顶栏「⌘K」提示按钮等外部触发点调用:打开命令面板。 */
export function openCommandPalette(): void {
  window.dispatchEvent(new Event(OPEN_EVENT));
}

/**
 * 命令面板开合状态 + 全局快捷键:
 * - ⌘K / Ctrl+K 切换开合(阻止浏览器默认,如 Chrome 聚焦地址栏);
 * - Esc 关闭(Modal 自身也处理,这里作兜底);
 * - 监听 openCommandPalette() 派发的事件,供顶栏按钮打开。
 * 只在 CommandPalette 内实例化一次。
 */
export function useCommandPalette() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && (e.key === 'k' || e.key === 'K')) {
        e.preventDefault();
        setOpen((o) => !o);
      } else if (e.key === 'Escape') {
        setOpen(false);
      }
    };
    const onOpen = () => setOpen(true);
    window.addEventListener('keydown', onKey);
    window.addEventListener(OPEN_EVENT, onOpen);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener(OPEN_EVENT, onOpen);
    };
  }, []);

  return { open, setOpen };
}
