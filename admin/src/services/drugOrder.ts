import { request } from '@umijs/max';
import { orderStatuses } from '@/contracts/order';

export type DrugOrderStatus = (typeof orderStatuses)[keyof typeof orderStatuses];

export interface DrugOrderItem {
  medication_id: number;
  name: string;
  specification: string;
  quantity: number;
  unit_price: number;
  subtotal: number;
}

export interface DrugOrder {
  id: number;
  patient_id: number;
  prescription_id: number;
  status: DrugOrderStatus;
  status_label: string;
  total_amount: number;
  created_at?: string;
  cancellable: boolean;
  payable: boolean;
  items: DrugOrderItem[];
}

export const listDrugOrders = (status?: DrugOrderStatus) =>
  request<DrugOrder[]>('/api/b/drug-orders', { params: status ? { status } : undefined });

export const getDrugOrder = (id: number) => request<DrugOrder>(`/api/b/drug-orders/${id}`);

export const cancelDrugOrder = (id: number) =>
  request<DrugOrder>(`/api/b/drug-orders/${id}/cancel`, { method: 'POST' });

export const completeDrugOrder = (id: number) =>
  request<DrugOrder>(`/api/b/drug-orders/${id}/complete`, { method: 'POST' });
