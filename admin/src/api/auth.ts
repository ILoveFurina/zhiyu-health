import { request } from './client'

export interface StaffProfile {
  username: string
  role: 'admin' | 'doctor'
  doctor_id: number | null
}

export async function login(username: string, password: string) {
  return request<{ access_token: string; token_type: 'bearer' }>('/b/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
}

export async function fetchProfile() {
  return request<StaffProfile>('/b/auth/me')
}
