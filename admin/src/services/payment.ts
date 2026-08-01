import { request } from '@umijs/max';
import type { PaymentStatus } from '@/contracts/payment';
export type { PaymentStatus } from '@/contracts/payment';

export interface Payment {
  id: number;
  appointment_id: number;
  amount: number;
  status: PaymentStatus;
  status_label: string;
  created_at?: string;
  paid_at?: string;
  payable: boolean;
}

export const listPayments = (status?: PaymentStatus) =>
  request<Payment[]>('/api/b/payments', { params: status ? { status } : undefined });

export const getPayment = (id: number) => request<Payment>(`/api/b/payments/${id}`);

export const payPayment = (id: number) =>
  request<Payment>(`/api/b/payments/${id}/pay`, { method: 'POST' });
