import {
  NaturalReceiptStatus,
  getNaturalImpression,
  putNaturalImpression,
  naturalUuid,
  type NaturalImpressionResult,
} from '../api/naturalExposure';
import { ReceiptQueueCore, type ReceiptTransport } from './receiptQueueCore';

export const NATURAL_RECEIPT_STORAGE_KEY = 'recsys.sandbox.natural.receipts.v1';
const accepted = (r: NaturalImpressionResult) =>
  r.status === NaturalReceiptStatus.ACCEPTED || r.status === NaturalReceiptStatus.ALREADY_ACCEPTED;
/** 自然协议没有账本/金额；只确认唯一曝光事实及其原回执。 */
export class NaturalReceiptQueue extends ReceiptQueueCore<NaturalImpressionResult> {
  constructor(
    storage: Storage | null,
    transport: ReceiptTransport<NaturalImpressionResult> = {
      put: putNaturalImpression,
      get: getNaturalImpression,
    },
    now: () => number = Date.now,
  ) {
    super(
      {
        storageKey: NATURAL_RECEIPT_STORAGE_KEY,
        accepted,
        validate: (entry, r) => {
          if (
            !r ||
            r.receiptId !== entry.receiptId ||
            r.qualificationId !== entry.childId ||
            !Object.values(NaturalReceiptStatus).includes(r.status) ||
            !Number.isFinite(Date.parse(r.decidedAt)) ||
            (accepted(r)
              ? r.eventId !== entry.childId ||
                !naturalUuid(r.canonicalReceiptId) ||
                !r.acceptedAt ||
                !Number.isFinite(Date.parse(r.acceptedAt)) ||
                Date.parse(r.acceptedAt) > Date.parse(r.decidedAt)
              : r.eventId !== null || r.canonicalReceiptId !== null || r.acceptedAt !== null)
          )
            throw new Error('自然回执结果不能确认，请按原 ID 查询');
        },
        known: ({
          receiptId,
          qualificationId,
          status,
          eventId,
          canonicalReceiptId,
          acceptedAt,
          decidedAt,
        }) => ({
          receiptId,
          qualificationId,
          status,
          eventId,
          canonicalReceiptId,
          acceptedAt,
          decidedAt,
        }),
      },
      storage,
      transport,
      now,
    );
  }
}
let singleton: NaturalReceiptQueue | undefined;
export function naturalReceiptQueue() {
  if (!singleton) {
    let storage: Storage | null;
    try {
      storage = sessionStorage;
    } catch {
      storage = null;
    }
    singleton = new NaturalReceiptQueue(storage);
    window.addEventListener('online', singleton.kick);
  }
  return singleton;
}
