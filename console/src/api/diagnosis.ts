import { http } from './client';
import type { DiagnosisReport } from './types';

// 一键诊断:复用服务健康 + 数据质量 + 离线评估 + 链路延迟等信号组装体检清单。
export async function getDiagnosis(): Promise<DiagnosisReport> {
  const { data } = await http.get<DiagnosisReport>('/api/console/diagnosis');
  return data;
}
