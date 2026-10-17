import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * 开发服务器固定 5173 —— 后端 CORS 白名单是
 * `http://localhost:5173,http://127.0.0.1:5173`（application.yml）。
 * 改端口会直接跨域失败，需同步改后端配置。
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
});
