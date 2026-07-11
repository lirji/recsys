import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

// 独立的 vitest 配置(与 vite.config.ts 分离,避免拉入 dev proxy / build.rollupOptions)。
// 复用 react plugin(处理 .tsx/JSX)与 @ → src alias,和线上构建保持同一模块解析口径。
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    css: false,
  },
});
