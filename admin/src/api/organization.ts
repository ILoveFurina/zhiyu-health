import { request } from './client'

export interface HospitalInput {
  name: string
  level: string
  address: string
  longitude: number
  latitude: number
}
export interface Hospital extends HospitalInput { id: number }

export interface DepartmentInput {
  hospital_id: number
  name: string
  floor: string
  location: string
}
export interface Department extends DepartmentInput { id: number }

export interface DoctorInput {
  department_id: number
  name: string
  title: string
  specialty: string
  photo_url: string
}
export interface Doctor extends DoctorInput { id: number }

function resourceApi<T, P>(resource: string) {
  return {
    list: () => request<T[]>(`/b/${resource}`),
    create: (payload: P) => request<T>(`/b/${resource}`, {
      method: 'POST', body: JSON.stringify(payload),
    }),
    update: (id: number, payload: P) => request<T>(`/b/${resource}/${id}`, {
      method: 'PUT', body: JSON.stringify(payload),
    }),
    remove: (id: number) => request<void>(`/b/${resource}/${id}`, { method: 'DELETE' }),
  }
}

export const hospitalApi = resourceApi<Hospital, HospitalInput>('hospitals')
export const departmentApi = resourceApi<Department, DepartmentInput>('departments')
export const doctorApi = resourceApi<Doctor, DoctorInput>('doctors')
