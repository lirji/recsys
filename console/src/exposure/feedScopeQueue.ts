import type { ExposureIdentity } from '../api/exposure';
import { receiptQueue } from './receiptQueue';
import { naturalReceiptQueue } from './naturalReceiptQueue';

/** 同一次身份解析后同时绑定两种协议；不创建第三个队列或新的资源预算。 */
export const feedScopeQueue = {
  get storageAvailable() {
    return receiptQueue().storageAvailable && naturalReceiptQueue().storageAvailable;
  },
  bind(identity: ExposureIdentity) {
    receiptQueue().bind(identity);
    naturalReceiptQueue().bind(identity);
  },
  subscribe(listener: () => void) {
    const ad = receiptQueue().subscribe(listener);
    const natural = naturalReceiptQueue().subscribe(listener);
    return () => {
      ad();
      natural();
    };
  },
};
