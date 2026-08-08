import type { RequestConfig, RunTimeLayoutConfig } from '@umijs/max';
import type { MenuDataItem } from '@ant-design/pro-layout';
import { history } from '@umijs/max';
import { App as AntdApp, Breadcrumb, Dropdown, message } from 'antd';
import { LogoutOutlined } from '@ant-design/icons';
import React from 'react';
import './global.css';
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
const ADMIN_PATHS = ['/hospitals', '/campuses', '/department-categories', '/standard-departments', '/departments', '/doctors', '/schedule-review', '/prescriptions', '/medications', '/drug-orders', '/payments', '/knowledge-graph', '/agent-trace', '/demo'];

// 顶栏面包屑：pathname -> [分组名, 页面名]
const ROUTE_GROUPS: Record<string, [string, string]> = {
  '/hospitals': ['组织管理', '医院管理'],
  '/campuses': ['组织管理', '院区管理'],
  '/department-categories': ['组织管理', '科室分类'],
  '/standard-departments': ['组织管理', '标准科室目录'],
  '/departments': ['组织管理', '科室管理'],
  '/doctors': ['组织管理', '医生管理'],
  '/schedule-review': ['业务管理', '排班审核'],
  '/prescriptions': ['业务管理', '电子处方审核'],
  '/medications': ['业务管理', '药品管理'],
  '/drug-orders': ['业务管理', '药品订单管理'],
  '/payments': ['业务管理', '收费管理'],
  '/workbench': ['业务管理', '接诊台'],
  '/schedule-table': ['业务管理', '排班表'],
  '/schedule-request': ['业务管理', '排班申请'],
  '/knowledge-graph': ['智能与日志', '医学知识图谱'],
  '/agent-trace': ['智能与日志', 'Agent 调用日志'],
  '/demo': ['智能与日志', '演示武器包'],
};

// menuDataRender 用：path -> 页面名
const ROUTE_NAMES: Record<string, string> = Object.fromEntries(
  Object.entries(ROUTE_GROUPS).map(([p, [, name]]) => [p, name]),
);

export interface InitialState {
  currentUser?: CurrentUser;
}

export function rootContainer(container: React.ReactNode) {
  return <AntdApp>{container}</AntdApp>;
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
  // navTheme/headerTheme/siderWidth 必须放 runtimeConfig 才生效（config.ts 的不生效）
  navTheme: 'light',
  headerTheme: 'light',
  siderWidth: 224,
  // 用 mix 布局以启用顶栏（side 布局在桌面端 Header 组件 return null，顶栏不渲染）。
  // splitMenus=false 让菜单仍全部留在侧栏，不在顶栏拆分，保留分组菜单结构。
  // fixedHeader=false：让顶栏随内容流式排布（不脱标），侧栏才能从 y=0 通顶，
  // logo 区落在左上角而非顶栏下方，对齐 option-a 的 sider 通顶 + 顶栏仅在内容列。
  layout: 'mix',
  splitMenus: false,
  fixedHeader: false,
  // 禁用侧栏收缩按钮（不需要收缩，去掉左下角箭头）
  collapsedButtonRender: false,
  // mix 布局顶栏默认会再渲染一份标题，与侧栏 logo 区重复，置空去掉顶栏标题
  headerTitleRender: () => null,
  // 侧栏菜单按分组渲染：用 menuDataRender 把扁平菜单重组为分组结构
  // （路由保持扁平，避免 Umi layout 插件对嵌套无 component 父级的白屏问题）
  // 按角色分流：admin 看三组管理菜单（接诊台不进 admin 菜单）；doctor 只看接诊台
  menu: { type: 'group' },
  menuDataRender: (): MenuDataItem[] => {
    const mk = (name: string, paths: string[]): MenuDataItem => ({
      name,
      path: `/${name}`,
      children: paths.map((p) => ({ name: ROUTE_NAMES[p], path: p })),
    });
    if (initialState?.currentUser?.role === 'doctor') {
      return [mk('业务管理', ['/workbench', '/schedule-table', '/schedule-request'])];
    }
    return [
      mk('组织管理', ['/hospitals', '/campuses', '/department-categories', '/standard-departments', '/departments', '/doctors']),
      mk('业务管理', ['/schedule-review', '/prescriptions', '/medications', '/drug-orders', '/payments']),
      mk('智能与日志', ['/knowledge-graph', '/agent-trace', '/demo']),
    ];
  },
  // 侧栏顶部 logo 区：绿底「智」方块 + 标题，对齐登录页品牌
  menuHeaderRender: () => (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '4px 0' }}>
      <span
        style={{
          width: 32, height: 32, borderRadius: 9, background: '#0e7a6c', color: '#fff',
          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          fontWeight: 700, fontSize: 16,
        }}
      >
        智
      </span>
      <span style={{ fontWeight: 600, color: '#123f38', fontSize: 15 }}>智愈管理后台</span>
    </div>
  ),
  // 侧栏底部页脚
  menuFooterRender: () => (
    <div style={{ padding: '14px 18px', borderTop: '1px solid #e6f2ee', fontSize: 12, color: '#5b7470' }}>
      智愈 · B 端组织与业务管理
    </div>
  ),
  // 顶栏中间：面包屑「分组 / 页面」
  headerContentRender: () => {
    const pathname = history.location.pathname;
    const group = ROUTE_GROUPS[pathname];
    if (!group) return null;
    return (
      <Breadcrumb
        style={{ fontSize: 13 }}
        items={[
          { title: <span style={{ color: '#5b7470' }}>{group[0]}</span> },
          { title: <span style={{ color: '#1f2d2a', fontWeight: 600 }}>{group[1]}</span> },
        ]}
      />
    );
  },
  // 顶栏右侧（对齐 option-a .top-right）：账号胶囊
  // 用 rightContentRender 精确控制位置，避免 actionsRender/avatarProps 在此布局中落到侧栏
  rightContentRender: () => (
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
      {/* 对齐 option-a .avatar：绿圆点首字母 + 用户名胶囊 */}
      <span
        className="zy-avatar"
        style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '4px 10px', borderRadius: 999, border: '1px solid #e6f2ee' }}
      >
        <span className="zy-avatar-dot">
          {(initialState?.currentUser?.username ?? 'A').charAt(0).toUpperCase()}
        </span>
        <span style={{ fontSize: 13, color: '#1f2d2a' }}>
          {initialState?.currentUser?.username ?? ''}
        </span>
      </span>
    </Dropdown>
  ),
});
