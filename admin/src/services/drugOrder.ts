import { request } from '@umijs/max';
import type { DrugOrderStatus, PickupMethod } from '@/contracts/order';
export type { DrugOrderStatus, PickupMethod } from '@/contracts/order';

export interface DrugOrderItem {
  medication_id: number;
  name: string;
  specification: string;
  quantity: number;
  unit_price: number;
  subtotal: number;
}

// append-only 履约事件（票 88）：状态 + 发生时间 + 操作 staff
export interface DrugOrderEvent {
  status: DrugOrderStatus;
  status_label?: string;
  occurred_at?: string;
  operator?: string | null;
}

export interface DrugOrder {
  id: number;
  patient_id: number;
  patient_name?: string;
  prescription_id: number | null;
  status: DrugOrderStatus;
  status_label: string;
  pickup_method?: PickupMethod;
  pickup_method_label?: string;
  medication_amount?: number;
  delivery_fee?: number;
  total_amount: number;
  payment_deadline?: string | null;
  created_at?: string;
  cancellable: boolean;
  payable: boolean;
  items: DrugOrderItem[];
  // 详情才有的快照字段（药房/院区/地址与配送收货信息）
  hospital_name?: string;
  campus_name?: string;
  pharmacy_name?: string;
  pickup_address?: string | null;
  receiver_name?: string | null;
  receiver_phone?: string | null;
  receiver_address?: string | null;
  carrier_name?: string | null;
  tracking_no?: string | null;
  events?: DrugOrderEvent[];
}

export interface DrugOrderPageResult {
  records: DrugOrder[];
  total: number;
}

// 分页形状以 server-java 为准；数组响应（未分页）也在此归一化
const normalizePage = (data: DrugOrder[] | DrugOrderPageResult): DrugOrderPageResult =>
  Array.isArray(data) ? { records: data, total: data.length } : data;

export const listDrugOrders = async (params?: {
  status?: DrugOrderStatus;
  pickup_method?: PickupMethod;
  page?: number;
  size?: number;
}) => normalizePage(await request<DrugOrder[] | DrugOrderPageResult>('/api/b/drug-orders', { params }));

export const getDrugOrder = (id: number) => request<DrugOrder>(`/api/b/drug-orders/${id}`);

export const cancelDrugOrder = (id: number) =>
  request<DrugOrder>(`/api/b/drug-orders/${id}/cancel`, { method: 'POST' });

// 模拟履约推进（票 88）：条件更新冲突返回 409，跳过全局 errorHandler 由页面弹出后端 message
export const advanceFulfillment = (id: number, decision: string) =>
  request<DrugOrder>(`/api/b/drug-orders/${id}/fulfillment`, {
    method: 'POST', data: { decision }, skipErrorHandler: true,
  });
