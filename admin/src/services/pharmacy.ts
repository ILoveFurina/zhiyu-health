import { request } from '@umijs/max';

// 票 88（ADR-0035）：院区药房库存。每院区恰好一个药房（campus 创建事务内生成，不可独立增删）；
// 药房药品维护价格/库存/在售，标准药品目录只承载名称/规格/处方属性。

export interface CampusPharmacy {
  id: number;
  campus_id: number;
  display_name: string;
  delivery_fee: number;
  estimated_delivery_minutes: number;
}

export interface CampusPharmacyInput {
  display_name: string;
  delivery_fee: number;
  estimated_delivery_minutes: number;
}

export interface PharmacyMedication {
  id: number;
  pharmacy_id: number;
  medication_id: number;
  name: string;
  specification: string;
  is_prescription: boolean;
  price: number;
  stock: number;
  is_on_sale: boolean;
}

export interface PharmacyMedicationInput {
  price: number;
  stock: number;
  is_on_sale: boolean;
}

export const getCampusPharmacy = (campusId: number) =>
  request<CampusPharmacy>(`/api/b/campuses/${campusId}/pharmacy`);

export const updateCampusPharmacy = (id: number, body: CampusPharmacyInput) =>
  request<CampusPharmacy>(`/api/b/campus-pharmacies/${id}`, { method: 'PUT', data: body });

export const listPharmacyMedications = (pharmacyId: number, keyword?: string) =>
  request<PharmacyMedication[]>(`/api/b/campus-pharmacies/${pharmacyId}/medications`, {
    params: keyword ? { keyword } : undefined,
  });

export const addPharmacyMedication = (pharmacyId: number, body: PharmacyMedicationInput & { medication_id: number }) =>
  request<PharmacyMedication>(`/api/b/campus-pharmacies/${pharmacyId}/medications`, {
    method: 'POST', data: body,
  });

export const updatePharmacyMedication = (id: number, body: PharmacyMedicationInput) =>
  request<PharmacyMedication>(`/api/b/pharmacy-medications/${id}`, { method: 'PUT', data: body });

// 409（已有历史处方/订单引用）不走全局 errorHandler，由页面提示「改为下架」
export const deletePharmacyMedication = (id: number) =>
  request(`/api/b/pharmacy-medications/${id}`, { method: 'DELETE', skipErrorHandler: true });
