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
      target: 'http://localhost:8080',
      changeOrigin: true,
    },
  },
  npmClient: 'npm',
});
