import { request } from '@umijs/max';
import { prescriptionDecisions, prescriptionStatuses } from '@/contracts/prescription';

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
  status: '待审核' | '已通过' | '已驳回';
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

export const fetchPendingPrescriptions = () =>
  request<Prescription[]>('/api/b/prescriptions', { params: { status: prescriptionStatuses.pending } });

export type ReviewDecision = typeof prescriptionDecisions.approve | typeof prescriptionDecisions.reject;

export const reviewPrescription = (id: number, decision: ReviewDecision, reason?: string) =>
  request<Prescription>(`/api/b/prescriptions/${id}/review`, {
    method: 'POST', data: { decision, reason },
  });
