import { request } from '@umijs/max';

// 票 88（ADR-0035）：medications 收敛为全平台标准药品目录，
// 不再承载价格/库存/启用状态（这些语义已下沉到院区药房药品 pharmacy_medications）。
export interface Medication {
  id: number;
  name: string;
  generic_name: string;
  specification: string;
  instructions: string;
  is_prescription: boolean;
}

export interface MedicationInput {
  is_prescription: boolean;
}

export const listMedications = () => request<Medication[]>('/api/b/medications');

export const updateMedication = (id: number, body: MedicationInput) =>
  request<Medication>(`/api/b/medications/${id}`, { method: 'PUT', data: body });
