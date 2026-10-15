import {
  ImpressionStatus,
  getImpression,
  putImpression,
  type ImpressionResult,
} from '../api/exposure';
import {
  ReceiptQueueCore,
  type ReceiptEntry as CoreEntry,
  type ReceiptTransport,
} from './receiptQueueCore';
export { ReceiptPhase, MAX_RECEIPTS } from './receiptQueueCore';
export type ReceiptEntry = CoreEntry<ImpressionResult>;
export const RECEIPT_STORAGE_KEY = 'recsys.sandbox.receipts.v1';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** 广告协议独立验证账本和精确金额，保持 S07 调用接口不变。 */
export class ReceiptQueue extends ReceiptQueueCore<ImpressionResult> {
  constructor(
    storage: Storage | null,
    transport: ReceiptTransport<ImpressionResult> = { put: putImpression, get: getImpression },
    now: () => number = Date.now,
  ) {
    super(
      {
        storageKey: RECEIPT_STORAGE_KEY,
        validate: validateResult,
        known: knownResult,
        accepted: (result) =>
          result.status === ImpressionStatus.ACCEPTED ||
          result.status === ImpressionStatus.ALREADY_ACCEPTED,
      },
      storage,
      transport,
      now,
    );
  }
}
function validateResult(
  entry: Pick<ReceiptEntry, 'childId' | 'receiptId'>,
  result: ImpressionResult,
) {
  if (
    !result ||
    result.receiptId !== entry.receiptId ||
    result.qualificationId !== entry.childId ||
    ![
      ImpressionStatus.ACCEPTED,
      ImpressionStatus.ALREADY_ACCEPTED,
      ImpressionStatus.POLICY_NOT_MET,
      ImpressionStatus.EXPIRED,
      ImpressionStatus.NOT_HELD,
    ].includes(result.status) ||
    !/^\d{1,14}\.\d{6}$/.test(result.chargedAmount) ||
    !Number.isFinite(Date.parse(result.decidedAt)) ||
    ((
      [ImpressionStatus.ACCEPTED, ImpressionStatus.ALREADY_ACCEPTED] as ImpressionResult['status'][]
    ).includes(result.status) &&
      (!result.ledgerId ||
        !UUID.test(result.ledgerId) ||
        !result.canonicalReceiptId ||
        !UUID.test(result.canonicalReceiptId) ||
        !result.acceptedAt ||
        !Number.isFinite(Date.parse(result.acceptedAt)) ||
        !result.budgetDate ||
        !/^\d{4}-\d{2}-\d{2}$/.test(result.budgetDate))) ||
    (!(
      [ImpressionStatus.ACCEPTED, ImpressionStatus.ALREADY_ACCEPTED] as ImpressionResult['status'][]
    ).includes(result.status) &&
      (result.chargedAmount !== '0.000000' ||
        result.ledgerId !== null ||
        result.acceptedAt !== null))
  ) {
    throw new Error('回执结果不能确认，请按原 ID 查询');
  }
}
/** 会话只保留已知结果字段，避免扩展响应/存储混入票据或无限增长的调试内容。 */
function knownResult(result: ImpressionResult): ImpressionResult {
  const {
    receiptId,
    qualificationId,
    status,
    ledgerId,
    canonicalReceiptId,
    chargedAmount,
    budgetDate,
    acceptedAt,
    decidedAt,
  } = result;
  return {
    receiptId,
    qualificationId,
    status,
    ledgerId,
    canonicalReceiptId,
    chargedAmount,
    budgetDate,
    acceptedAt,
    decidedAt,
  };
}
let singleton: ReceiptQueue | undefined;
export function receiptQueue() {
  if (!singleton) {
    let storage: Storage | null;
    try {
      storage = sessionStorage;
    } catch {
      storage = null;
    }
    singleton = new ReceiptQueue(storage);
    window.addEventListener('online', singleton.kick);
  }
  return singleton;
}
