import { request } from '@umijs/max';

export interface PrescriptionTemplateItem {
  id: number;
  medication_id: number;
  medication_name: string;
  specification: string;
  dosage: string;
  frequency: string;
  duration: string;
  notes?: string;
}

export interface PrescriptionTemplate {
  id: number;
  name: string;
  doctor_id: number;
  created_at: string;
  items: PrescriptionTemplateItem[];
}

export interface PrescriptionTemplateInput {
  name: string;
  items: Array<{
    medication_id: number;
    dosage: string;
    frequency: string;
    duration: string;
    notes?: string;
  }>;
}

const BASE = '/api/b/reception/prescription-templates';

export const listTemplates = () => request<PrescriptionTemplate[]>(BASE);

export const createTemplate = (data: PrescriptionTemplateInput) =>
  request<PrescriptionTemplate>(BASE, { method: 'POST', data });

export const updateTemplate = (id: number, data: PrescriptionTemplateInput) =>
  request<PrescriptionTemplate>(`${BASE}/${id}`, { method: 'PUT', data });

export const deleteTemplate = (id: number) =>
  request<void>(`${BASE}/${id}`, { method: 'DELETE' });
