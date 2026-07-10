import { http } from './client';
import type {
  ServiceHealth,
  SystemApiEndpoint,
  SystemCommandGroup,
  SystemMetrics,
  SystemModule,
  SystemOverview,
} from './types';

export async function getSystemOverview(): Promise<SystemOverview> {
  const { data } = await http.get<SystemOverview>('/api/console/system/overview');
  return data;
}

export async function getSystemModules(): Promise<SystemModule[]> {
  const { data } = await http.get<SystemModule[]>('/api/console/system/modules');
  return data;
}

export async function getSystemHealth(): Promise<ServiceHealth[]> {
  const { data } = await http.get<ServiceHealth[]>('/api/console/system/health');
  return data;
}

export async function getSystemMetrics(): Promise<SystemMetrics> {
  const { data } = await http.get<SystemMetrics>('/api/console/system/metrics');
  return data;
}

export async function getSystemApis(): Promise<SystemApiEndpoint[]> {
  const { data } = await http.get<SystemApiEndpoint[]>('/api/console/system/apis');
  return data;
}

export async function getSystemCommands(): Promise<SystemCommandGroup[]> {
  const { data } = await http.get<SystemCommandGroup[]>('/api/console/system/commands');
  return data;
}
