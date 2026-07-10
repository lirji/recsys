import { http } from './client';

export interface InterestsResp {
  userId: number;
  categories: string[];
  ok?: boolean;
}

export async function getInterests(userId: number): Promise<InterestsResp> {
  const { data } = await http.get<InterestsResp>(`/api/user/${userId}/interests`);
  return data;
}

export async function saveInterests(userId: number, categories: string[]): Promise<InterestsResp> {
  const { data } = await http.post<InterestsResp>(`/api/user/${userId}/interests`, categories);
  return data;
}
