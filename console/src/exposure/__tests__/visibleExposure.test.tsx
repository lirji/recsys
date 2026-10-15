import { StrictMode, useState } from 'react';
import { act, cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useVisibleExposure } from '../../hooks/useVisibleExposure';
let last: Observer;
const remember = (observer: Observer) => {
  last = observer;
};
class Observer {
  target!: Element;
  callback: IntersectionObserverCallback;
  constructor(callback: IntersectionObserverCallback) {
    this.callback = callback;
    remember(this);
  }
  observe(target: Element) {
    this.target = target;
  }
  unobserve() {}
  disconnect() {}
  emit(ratio: number) {
    this.callback(
      [
        {
          target: this.target,
          isIntersecting: ratio > 0,
          intersectionRatio: ratio,
        } as IntersectionObserverEntry,
      ],
      this as unknown as IntersectionObserver,
    );
  }
}
function Creative({
  visible,
  enabled = true,
}: {
  visible: (duration: number, ratio: number) => boolean;
  enabled?: boolean;
}) {
  const [expiresAt] = useState(() => new Date(Date.now() + 300000).toISOString());
  const { ref, phase } = useVisibleExposure(enabled, expiresAt, visible);
  return (
    <div ref={ref} data-phase={phase}>
      真实创意文本
    </div>
  );
}
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date', 'performance'] });
  vi.stubGlobal('IntersectionObserver', Observer);
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
it('低于一半或未满一秒不采集，达标使用保守比例下界', () => {
  const visible = vi.fn(() => true);
  render(<Creative visible={visible} />);
  act(() => {
    last.emit(0.49);
    vi.advanceTimersByTime(2000);
  });
  expect(visible).not.toHaveBeenCalled();
  act(() => {
    last.emit(0.75);
    vi.advanceTimersByTime(999);
  });
  expect(visible).not.toHaveBeenCalled();
  act(() => {
    vi.advanceTimersByTime(1);
  });
  expect(visible).toHaveBeenCalledWith(1000, 5000);
  act(() => {
    last.emit(0);
    last.emit(1);
    vi.advanceTimersByTime(2000);
  });
  expect(visible).toHaveBeenCalledTimes(1);
});
it('隐藏重置，恢复须取得新的交叉样本并重新连续计时', () => {
  const visible = vi.fn(() => true);
  render(<Creative visible={visible} />);
  act(() => {
    last.emit(1);
    vi.advanceTimersByTime(800);
  });
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(2000);
  });
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'));
    vi.advanceTimersByTime(1200);
  });
  expect(visible).not.toHaveBeenCalled();
  act(() => {
    last.emit(1);
    vi.advanceTimersByTime(1000);
  });
  expect(visible).toHaveBeenCalledTimes(1);
});
it('StrictMode、卸载和无资格不凭挂载上报', () => {
  const visible = vi.fn(() => true);
  const view = render(
    <StrictMode>
      <Creative visible={visible} />
    </StrictMode>,
  );
  act(() => {
    last.emit(1);
    vi.advanceTimersByTime(500);
  });
  view.unmount();
  act(() => {
    vi.advanceTimersByTime(2000);
  });
  expect(visible).not.toHaveBeenCalled();
  render(<Creative visible={visible} enabled={false} />);
  act(() => {
    vi.advanceTimersByTime(2000);
  });
  expect(visible).not.toHaveBeenCalled();
});

it('pagehide 先于 visibilitychange 也停止计时，pageshow 必须重新测量', () => {
  const visible = vi.fn(() => true);
  render(<Creative visible={visible} />);
  act(() => {
    last.emit(1);
    vi.advanceTimersByTime(800);
    window.dispatchEvent(new Event('pagehide'));
    last.emit(1);
    vi.advanceTimersByTime(2000);
  });
  expect(visible).not.toHaveBeenCalled();
  act(() => {
    window.dispatchEvent(new Event('pageshow'));
    last.emit(1);
    vi.advanceTimersByTime(999);
  });
  expect(visible).not.toHaveBeenCalled();
  act(() => {
    vi.advanceTimersByTime(1);
  });
  expect(visible).toHaveBeenCalledTimes(1);
});
