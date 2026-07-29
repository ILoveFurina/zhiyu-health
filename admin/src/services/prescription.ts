import { request } from '@umijs/max';
import { contraindicationDecisions, contraindicationMessageTypes } from '@/contracts/contraindication';
import { prescriptionDecisions, prescriptionStatuses, prescriptionStatusLabels } from '@/contracts/prescription';

type PrescriptionStatus = (typeof prescriptionStatusLabels)[keyof typeof prescriptionStatusLabels];

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
  appointment_id: number;
  status: PrescriptionStatus;
  notes?: string;
  interpretation?: string;
  disclaimer?: string;
  patient_nickname?: string;
  doctor_name?: string;
  date?: string;
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

export const fetchPendingPrescriptions = () =>
  request<Prescription[]>('/api/b/prescriptions', { params: { status: prescriptionStatuses.pending } });

export type ReviewDecision = typeof prescriptionDecisions.approve | typeof prescriptionDecisions.reject;

export const reviewPrescription = (id: number, decision: ReviewDecision, reason?: string) =>
  request<Prescription>(`/api/b/prescriptions/${id}/review`, {
    method: 'POST', data: { decision, reason },
  });
