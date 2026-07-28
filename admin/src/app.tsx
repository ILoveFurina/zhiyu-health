import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import { history } from '@umijs/max';
import { Dropdown, message } from 'antd';
import { LogoutOutlined } from '@ant-design/icons';
import React from 'react';
import { fetchMe, type CurrentUser } from '@/services/auth';
import {
  clearToken,
  getCachedUser,
  getToken,
  homeByRole,
  resolveUser,
  setCachedUser,
} from '@/utils/session';

const LOGIN_PATH = '/login';
const ADMIN_PATHS = ['/hospitals', '/departments', '/doctors'];

export interface InitialState {
  currentUser?: CurrentUser;
}

export async function getInitialState(): Promise<InitialState> {
  if (!getToken()) return {};
  try {
    const currentUser = await fetchMe();
    setCachedUser(currentUser);
    return { currentUser };
  } catch {
    // token 失效（过期/后端拒绝），清理后按未登录处理，由守卫接管跳转
    clearToken();
    return {};
  }
}

export const request: RequestConfig = {
  requestInterceptors: [
    (config: any) => {
      const token = getToken();
      if (token) {
        config.headers = { ...config.headers, Authorization: `Bearer ${token}` };
      }
      return config;
    },
  ],
  errorConfig: {
    errorHandler: (error: any) => {
      const { response } = error;
      if (response?.status === 401 && getToken() && history.location.pathname !== LOGIN_PATH) {
        clearToken();
        history.replace(LOGIN_PATH);
      }
      const detail = response?.data?.detail;
      message.error(typeof detail === 'string' ? detail : '请求失败，请稍后重试');
    },
  },
};

// 路由守卫：未登录一律去 /login；已登录访问 /login 或 / 按角色落首页；doctor 禁入组织管理页
export function onRouteChange({ location }: { location: { pathname: string } }) {
  const { pathname } = location;
  const loggedIn = !!getToken();
  if (!loggedIn) {
    if (pathname !== LOGIN_PATH) history.replace(LOGIN_PATH);
    return;
  }
  if (pathname === LOGIN_PATH || pathname === '/') {
    void resolveUser().then((user) => {
      // resolveUser 失败说明 token 失效，回登录页
      history.replace(user ? homeByRole(user.role) : LOGIN_PATH);
    });
    return;
  }
  if (getCachedUser()?.role !== 'admin' && ADMIN_PATHS.some((p) => pathname.startsWith(p))) {
    history.replace('/workbench');
  }
}

export const layout: RunTimeLayoutConfig = ({ initialState, setInitialState }) => ({
  avatarProps: {
    title: initialState?.currentUser?.username ?? '',
    render: (_, dom) => (
      <Dropdown
        menu={{
          items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }],
          onClick: ({ key }) => {
            if (key !== 'logout') return;
            clearToken();
            setCachedUser(undefined);
            setInitialState({});
            history.replace(LOGIN_PATH);
          },
        }}
      >
        {dom}
      </Dropdown>
    ),
  },
});
