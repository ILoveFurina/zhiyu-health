import { request } from '@umijs/max';

// 角色类型由契约 staff-roles.json 推导（票 88：新增 pharmacist 全局药师）
export type { Role } from '@/contracts/staff-role';
import type { Role } from '@/contracts/staff-role';

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
