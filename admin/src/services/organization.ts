import { request } from '@umijs/max';

export interface Hospital {
  id: number;
  name: string;
  level: string;
  address: string;
  longitude: number;
  latitude: number;
}

export interface Department {
  id: number;
  hospital_id: number;
  name: string;
  floor: string;
  location: string;
}

export interface Doctor {
  id: number;
  department_id: number;
  name: string;
  title: string;
  specialty: string;
  photo_url: string;
}

export function listHospitals() {
  return request<Hospital[]>('/api/b/hospitals');
}

export function createHospital(body: Omit<Hospital, 'id'>) {
  return request<Hospital>('/api/b/hospitals', { method: 'POST', data: body });
}

export function updateHospital(id: number, body: Omit<Hospital, 'id'>) {
  return request<Hospital>(`/api/b/hospitals/${id}`, { method: 'PUT', data: body });
}

export function removeHospital(id: number) {
  return request(`/api/b/hospitals/${id}`, { method: 'DELETE' });
}

export function listDepartments() {
  return request<Department[]>('/api/b/departments');
}

export function createDepartment(body: Omit<Department, 'id'>) {
  return request<Department>('/api/b/departments', { method: 'POST', data: body });
}

export function updateDepartment(id: number, body: Omit<Department, 'id'>) {
  return request<Department>(`/api/b/departments/${id}`, { method: 'PUT', data: body });
}

export function removeDepartment(id: number) {
  return request(`/api/b/departments/${id}`, { method: 'DELETE' });
}

export function listDoctors() {
  return request<Doctor[]>('/api/b/doctors');
}

export function createDoctor(body: Omit<Doctor, 'id'>) {
  return request<Doctor>('/api/b/doctors', { method: 'POST', data: body });
}

export function updateDoctor(id: number, body: Omit<Doctor, 'id'>) {
  return request<Doctor>(`/api/b/doctors/${id}`, { method: 'PUT', data: body });
}

export function removeDoctor(id: number) {
  return request(`/api/b/doctors/${id}`, { method: 'DELETE' });
}
