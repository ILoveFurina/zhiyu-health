import { defineConfig } from '@umijs/max';
import routes from './routes';

export default defineConfig({
  antd: {
    // 医疗白主题：色板源自登录页（主绿 #0e7a6c / 深绿 #123f38 / 底 #f3f8f6），
    // 全站统一，消除除登录页外的 AntD 默认蓝。
    theme: {
      token: {
        colorPrimary: '#0e7a6c',
        colorSuccess: '#0e7a6c',
        colorInfo: '#1d6fb8',
        colorWarning: '#d4881f',
        colorError: '#d4605a',
        colorTextBase: '#1f2d2a',
        colorBgLayout: '#f3f8f6',
        borderRadius: 10,
        wireframe: false,
      },
    },
  },
  access: {},
  model: {},
  initialState: {},
  request: {},
  layout: {
    title: '智愈管理后台',
    // navTheme/headerTheme/siderWidth 在 src/app.tsx 的 layout runtimeConfig 中设置
    // （config.ts 的 layout 静态块只取 title，其余 ProLayout prop 不生效）
  },
  routes,
  // hash 路由：build 产物可直接静态托管，不依赖后端 history 回退
  history: { type: 'hash' },
  proxy: {
    '/api': {
      target: process.env.SERVER_JAVA_BASE_URL || 'http://localhost:8080',
      changeOrigin: true,
    },
  },
  npmClient: 'npm',
  // 禁用 MFSU：路由结构变更后 mf-va_remoteEntry.js 易损坏导致白屏
  mfsu: false,
  // 多 async chunk 下 esbuild minify helper 可能被分别注入同名定义，产物校验（esbuildHelperChecker）要求统一 IIFE 包裹
  esbuildMinifyIIFE: true,
});
