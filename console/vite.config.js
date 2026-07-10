var _a;
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';
// 前后端分离:独立 Vite 工程,构建产物输出到本目录 dist/,由 nginx 同源托管(见 Dockerfile/nginx.conf)。
// dev 阶段所有 /api 请求 proxy 到网关 :8080(RECSYS_GATEWAY 可覆盖),网关分发到 rec-engine/behavior/advertiser/console
// —— 浏览器只与 :5173 通信,零 CORS。
export default defineConfig({
    plugins: [react()],
    base: '/',
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
        },
    },
    build: {
        outDir: 'dist',
        emptyOutDir: true,
        chunkSizeWarningLimit: 1500,
        rollupOptions: {
            output: {
                // 拆分厂商包改善缓存;echarts 只被 lazy 页面引用,自动进按需 chunk。
                manualChunks: {
                    'react-vendor': ['react', 'react-dom', 'react-router-dom'],
                    antd: ['antd', '@ant-design/icons'],
                },
            },
        },
    },
    server: {
        port: 5173,
        proxy: {
            '/api': {
                target: (_a = process.env.RECSYS_GATEWAY) !== null && _a !== void 0 ? _a : 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
});
