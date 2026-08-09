import { request } from '@umijs/max';

export interface Medication {
  id: number;
  name: string;
  generic_name: string;
  specification: string;
  instructions: string;
  price: number;
  stock: number;
  is_active: boolean;
  is_prescription: boolean;
}

export interface MedicationInput {
  price: number;
  stock: number;
  is_active: boolean;
  is_prescription: boolean;
}

export const listMedications = () => request<Medication[]>('/api/b/medications');

export const updateMedication = (id: number, body: MedicationInput) =>
  request<Medication>(`/api/b/medications/${id}`, { method: 'PUT', data: body });
