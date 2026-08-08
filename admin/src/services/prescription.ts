import { request } from '@umijs/max';
import { contraindicationDecisions, contraindicationMessageTypes } from '@/contracts/contraindication';
import {
  prescriptionDecisions,
  prescriptionStatuses,
  prescriptionStatusLabels,
  type PrescriptionSourceType,
} from '@/contracts/prescription';

type PrescriptionStatus = (typeof prescriptionStatusLabels)[keyof typeof prescriptionStatusLabels];
export type PrescriptionStatusCode = (typeof prescriptionStatuses)[keyof typeof prescriptionStatuses];

export interface Medication {
  id: number;
  name: string;
  generic_name: string;
  specification: string;
  instructions: string;
}

export interface PrescriptionItem {
  medication_id: number;
  name: string;
  specification: string;
  dosage: string;
  frequency: string;
  duration: string;
  notes?: string;
}

export interface Prescription {
  id: number;
  // 线下挂号与在线问诊两个来源外键二选一，必有一个非空
  appointment_id?: number | null;
  online_consultation_id?: number | null;
  source_type: PrescriptionSourceType;
  source_type_label: string;
  status: PrescriptionStatus;
  notes?: string;
  interpretation?: string;
  disclaimer?: string;
  patient_nickname?: string;
  doctor_name?: string;
  date?: string;
  diagnosis?: string | null;
  advice?: string | null;
  items: PrescriptionItem[];
}

export interface PrescriptionInput {
  notes?: string;
  items: Array<{
    medication_id: number;
    dosage: string;
    frequency: string;
    duration: string;
    notes?: string;
  }>;
}

export const fetchMedications = () => request<Medication[]>('/api/b/reception/medications');

export const createPrescription = (appointmentId: number, data: PrescriptionInput) =>
  request<Prescription>(`/api/b/reception/appointments/${appointmentId}/prescriptions`, {
    method: 'POST', data,
  });

export type SafetyDecision = (typeof contraindicationDecisions)[keyof typeof contraindicationDecisions];
export type SafetyMessageType = (typeof contraindicationMessageTypes)[keyof typeof contraindicationMessageTypes];

export interface SafetyCheckResult {
  decision: SafetyDecision;
  message_type: SafetyMessageType;
  blocked: boolean;
  reasons: string[];
  message: string;
  advice?: string | null;
}

export const checkPrescriptionSafety = (appointmentId: number, medicationIds: number[]) =>
  request<SafetyCheckResult>(`/api/b/reception/appointments/${appointmentId}/contraindication-check`, {
    method: 'POST', data: { medication_ids: medicationIds },
  });

// 在线问诊开方：请求/响应形状与线下端点一致，仅路径与来源外键不同
export const createOnlinePrescription = (onlineConsultationId: number, data: PrescriptionInput) =>
  request<Prescription>(`/api/b/reception/online-consultations/${onlineConsultationId}/prescriptions`, {
    method: 'POST', data,
  });

export const checkOnlinePrescriptionSafety = (onlineConsultationId: number, medicationIds: number[]) =>
  request<SafetyCheckResult>(`/api/b/reception/online-consultations/${onlineConsultationId}/contraindication-check`, {
    method: 'POST', data: { medication_ids: medicationIds },
  });

// 问诊关联处方的审核状态（票 60 A4）：状态码为契约枚举，标签以接口下发 status_label 为准；
// 无处方时 prescription 为 null，属正常态
export interface ConsultationPrescription {
  id: number;
  status: PrescriptionStatusCode;
  status_label: string;
  review_reason: string | null;
}

export const fetchOnlineConsultationPrescription = (onlineConsultationId: number) =>
  request<{ prescription: ConsultationPrescription | null }>(
    // 接诊台命名空间（/api/b/reception/**）：AdminInterceptor 豁免，接诊医生可达
    `/api/b/reception/online-consultations/${onlineConsultationId}/prescription`,
  );

export const fetchPendingPrescriptions = () =>
  request<Prescription[]>('/api/b/prescriptions', { params: { status: prescriptionStatuses.pending } });

export type ReviewDecision = typeof prescriptionDecisions.approve | typeof prescriptionDecisions.reject;

export const reviewPrescription = (id: number, decision: ReviewDecision, reason?: string) =>
  request<Prescription>(`/api/b/prescriptions/${id}/review`, {
    method: 'POST', data: { decision, reason },
  });
