import { fetchMe, type CurrentUser } from '@/services/auth';

const TOKEN_KEY = 'staff_token';

// onRouteChange 等运行时回调拿不到 initialState，用模块级缓存共享当前用户
let cachedUser: CurrentUser | undefined;

export function setCachedUser(user?: CurrentUser) {
  cachedUser = user;
}

export function getCachedUser() {
  return cachedUser;
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

// 各角色登录落点（票 88）：admin 进组织管理，pharmacist 进处方审核，doctor 进接诊台
export function homeByRole(role?: string) {
  if (role === 'admin') return '/hospitals';
  if (role === 'pharmacist') return '/prescriptions';
  return '/workbench';
}

// 首屏 onRouteChange 可能先于 getInitialState 触发，cachedUser 未就绪时补拉一次
export async function resolveUser(): Promise<CurrentUser | undefined> {
  if (cachedUser) return cachedUser;
  try {
    const currentUser = await fetchMe();
    setCachedUser(currentUser);
    return currentUser;
  } catch {
    clearToken();
    return undefined;
  }
}
