import { http } from './client';
import { ClientError } from './errors';
import type { ExperimentSnapshot, ExperimentWriteResult } from './types';

export async function getExperiment(): Promise<ExperimentSnapshot> {
  const { data } = await http.get<ExperimentSnapshot>('/api/experiment');
  return data;
}

export async function setGlobalEnabled(value: boolean): Promise<ExperimentWriteResult> {
  const { data } = await http.post<ExperimentWriteResult>('/api/experiment/enabled', null, {
    params: { value },
  });
  return confirmedWrite(data);
}

export async function setLayerEnabled(
  layer: string,
  value: boolean,
): Promise<ExperimentWriteResult> {
  const { data } = await http.post<ExperimentWriteResult>(
    `/api/experiment/${encodeURIComponent(layer)}/enabled`,
    null,
    { params: { value } },
  );
  return confirmedWrite(data);
}

export async function setVariantWeight(
  layer: string,
  variant: string,
  value: number,
): Promise<ExperimentWriteResult> {
  const { data } = await http.post<ExperimentWriteResult>(
    `/api/experiment/${encodeURIComponent(layer)}/${encodeURIComponent(variant)}/weight`,
    null,
    { params: { value } },
  );
  return confirmedWrite(data);
}

export async function clearOverride(): Promise<ExperimentWriteResult> {
  const { data } = await http.delete<ExperimentWriteResult>('/api/experiment/override');
  return confirmedWrite(data);
}

/** 兼容旧reason-only拒绝；写入ACK缺失或损坏时不能显示成功。 */
function confirmedWrite(data: ExperimentWriteResult): ExperimentWriteResult {
  if (data?.ok === true) return data;
  throw new ClientError(
    data?.ok === false && data.code === 'EXPERIMENT_STORE_UNAVAILABLE'
      ? 'EXPERIMENT_STORE_UNAVAILABLE'
      : 'EXPERIMENT_WRITE_UNCONFIRMED',
  );
}
