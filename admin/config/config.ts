import { defineConfig } from '@umijs/max';
import routes from './routes';

export default defineConfig({
  antd: {},
  access: {},
  model: {},
  initialState: {},
  request: {},
  layout: {
    title: '智愈管理后台',
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
  // 多 async chunk 下 esbuild minify helper 可能被分别注入同名定义，产物校验（esbuildHelperChecker）要求统一 IIFE 包裹
  esbuildMinifyIIFE: true,
});
