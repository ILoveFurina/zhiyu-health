import { request } from '@umijs/max';

export interface Hospital {
  id: number;
  name: string;
  level: string;
}

export interface Campus {
  id: number;
  hospital_id: number;
  name: string;
  city_code: string;
  city_name: string;
  address: string;
  longitude: number;
  latitude: number;
  floor: string;
  materials: string;
  precautions: string;
}

export interface DepartmentCategory {
  id: number;
  hospital_id: number;
  name: string;
  sort_order: number;
}

export interface StandardDepartment {
  id: number;
  category: string;
  name: string;
  sort_order: number;
}

export interface Department {
  id: number;
  campus_id: number;
  category_id: number;
  standard_department_id: number;
  name: string;
  floor: string;
  location: string;
}

export interface Doctor {
  id: number;
  department_id: number;
  name: string;
  title: string;
  registration_fee: number;
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

export function listCampuses() {
  return request<Campus[]>('/api/b/campuses');
}

export function createCampus(body: Omit<Campus, 'id'>) {
  return request<Campus>('/api/b/campuses', { method: 'POST', data: body });
}

export function updateCampus(id: number, body: Omit<Campus, 'id'>) {
  return request<Campus>(`/api/b/campuses/${id}`, { method: 'PUT', data: body });
}

export function removeCampus(id: number) {
  return request(`/api/b/campuses/${id}`, { method: 'DELETE' });
}

export function listDepartmentCategories() {
  return request<DepartmentCategory[]>('/api/b/department-categories');
}

export function createDepartmentCategory(body: Omit<DepartmentCategory, 'id'>) {
  return request<DepartmentCategory>('/api/b/department-categories', { method: 'POST', data: body });
}

export function updateDepartmentCategory(id: number, body: Omit<DepartmentCategory, 'id'>) {
  return request<DepartmentCategory>(`/api/b/department-categories/${id}`, { method: 'PUT', data: body });
}

export function removeDepartmentCategory(id: number) {
  return request(`/api/b/department-categories/${id}`, { method: 'DELETE' });
}

export function listStandardDepartments() {
  return request<StandardDepartment[]>('/api/b/standard-departments');
}

export function createStandardDepartment(body: Omit<StandardDepartment, 'id'>) {
  return request<StandardDepartment>('/api/b/standard-departments', { method: 'POST', data: body });
}

export function updateStandardDepartment(id: number, body: Omit<StandardDepartment, 'id'>) {
  return request<StandardDepartment>(`/api/b/standard-departments/${id}`, { method: 'PUT', data: body });
}

export function removeStandardDepartment(id: number) {
  return request(`/api/b/standard-departments/${id}`, { method: 'DELETE' });
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
