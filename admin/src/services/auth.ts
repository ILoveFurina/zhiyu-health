import { request } from '@umijs/max';

export type Role = 'admin' | 'doctor';

export interface CurrentUser {
  username: string;
  role: Role;
  doctor_id: number | null;
}

export interface LoginBody {
  username: string;
  password: string;
}

export interface LoginResult {
  access_token: string;
  token_type: string;
}

export function login(body: LoginBody) {
  return request<LoginResult>('/api/b/auth/login', { method: 'POST', data: body });
}

export function fetchMe() {
  return request<CurrentUser>('/api/b/auth/me');
}
