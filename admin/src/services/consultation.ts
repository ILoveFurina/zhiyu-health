import { request } from '@umijs/max';
import {
  consultationStatusLabels,
  consultMethods,
  senderTypes,
} from '@/contracts/consultation';

export type ConsultationStatus = keyof typeof consultationStatusLabels;
export type ConsultMethod = (typeof consultMethods)[keyof typeof consultMethods];
export type SenderType = (typeof senderTypes)[keyof typeof senderTypes];

export interface ConsultationSummary {
  chief_complaint: string;
  present_illness: string;
  allergy_history: string;
}

export interface HealthProfile {
  display_name: string;
  gender: string;
  birth_date: string;
  relationship: string;
  allergies: string[];
}

export interface PoolItem {
  id: number;
  status: ConsultationStatus;
  status_label: string;
  standard_department_id: number;
  standard_department_name: string;
  summary: ConsultationSummary | null;
  summary_disclaimer: string;
  patient: { nickname: string };
  health_profile: HealthProfile;
  created_at: string;
  expires_at: string;
  // /mine 列表附加字段
  consult_method?: ConsultMethod | null;
  consult_method_label?: string | null;
  accepted_at?: string | null;
  completed_at?: string | null;
}

export interface ConsultationDetail extends PoolItem {
  consult_method: ConsultMethod | null;
  consult_method_label: string | null;
  method_started_at: string | null;
  diagnosis: string | null;
  advice: string | null;
  accepted_at: string | null;
  completed_at: string | null;
  cancelled_at: string | null;
}

export interface ConsultationMessage {
  id: number;
  sender_type: SenderType;
  content: string;
  created_at: string;
}

const BASE = '/api/b/reception/online-consultations';

export function fetchPool() {
  return request<{ consultations: PoolItem[] }>(`${BASE}/pool`);
}

export function fetchMine(status?: ConsultationStatus) {
  return request<{ consultations: PoolItem[] }>(`${BASE}/mine`, { params: { status } });
}

export function fetchDetail(id: number) {
  return request<{ consultation: ConsultationDetail }>(`${BASE}/${id}`);
}

export function accept(id: number) {
  return request<{ consultation: ConsultationDetail }>(`${BASE}/${id}/accept`, { method: 'POST' });
}

export function startMethod(id: number, method: ConsultMethod) {
  return request<{ consultation: ConsultationDetail }>(`${BASE}/${id}/start-method`, {
    method: 'POST',
    data: { method },
  });
}

export function fetchMessages(id: number, afterId = 0) {
  return request<{ messages: ConsultationMessage[] }>(`${BASE}/${id}/messages`, {
    params: { after_id: afterId },
  });
}

export function sendMessage(id: number, content: string) {
  return request<{ message: ConsultationMessage }>(`${BASE}/${id}/messages`, {
    method: 'POST',
    data: { content },
  });
}

export function complete(id: number, data: { diagnosis: string; advice: string }) {
  return request<{ consultation: ConsultationDetail }>(`${BASE}/${id}/complete`, {
    method: 'POST',
    data,
  });
}
