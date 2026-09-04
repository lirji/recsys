import { http } from './client';
import type { ExperimentSnapshot, ExperimentWriteResult } from './types';

export async function getExperiment(): Promise<ExperimentSnapshot> {
  const { data } = await http.get<ExperimentSnapshot>('/api/experiment');
  return data;
}

export async function setGlobalEnabled(value: boolean): Promise<ExperimentWriteResult> {
  const { data } = await http.post<ExperimentWriteResult>('/api/experiment/enabled', null, { params: { value } });
  return data;
}

export async function setLayerEnabled(layer: string, value: boolean): Promise<ExperimentWriteResult> {
  const { data } = await http.post<ExperimentWriteResult>(
    `/api/experiment/${encodeURIComponent(layer)}/enabled`,
    null,
    { params: { value } },
  );
  return data;
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
  return data;
}

export async function clearOverride(): Promise<ExperimentWriteResult> {
  const { data } = await http.delete<ExperimentWriteResult>('/api/experiment/override');
  return data;
}
