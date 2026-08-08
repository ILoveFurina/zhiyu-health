import { request } from '@umijs/max';
import type { AppointmentStatusCode } from '@/contracts/appointment';

export interface ReceptionSchedule {
  id: number;
  time_slot: string;
  total_slots: number;
  remaining_slots: number;
  active: boolean;
}

export interface ReceptionAppointment {
  id: number;
  schedule_id: number;
  patient_nickname: string;
  sequence_number: number;
  status_code: AppointmentStatusCode;
  status: string;
  /** 关联电子处方状态（PENDING/APPROVED/REJECTED），未开方为 null */
  prescription_status: string | null;
  schedule_date: string;
  time_slot: string;
  condition_summary?: string;
  summary_disclaimer: string;
}

export interface ReceptionDashboard {
  date: string;
  schedules: ReceptionSchedule[];
  appointments: ReceptionAppointment[];
}

export interface AppointmentDetail {
  appointment: ReceptionAppointment;
  diagnosis?: string;
  advice?: string;
  completed_at?: string;
}

export function fetchReceptionDashboard() {
  return request<ReceptionDashboard>('/api/b/reception');
}

export function fetchAppointmentDetail(id: number) {
  return request<AppointmentDetail>(`/api/b/reception/appointments/${id}`);
}

export function completeAppointment(id: number, data: { diagnosis: string; advice: string }) {
  return request<AppointmentDetail>(`/api/b/reception/appointments/${id}/complete`, {
    method: 'POST',
    data,
  });
}

export function callAppointment(id: number) {
  return request<AppointmentDetail>(`/api/b/reception/appointments/${id}/call`, { method: 'POST' });
}
