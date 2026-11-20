import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时由 Vite 代理 /api 到后端 8089，浏览器视角同源，
// Session Cookie 与 CSRF 令牌都能正常工作，因此后端不需要开放 CORS。
export default defineConfig({
    plugins: [vue()],
    server: {
        port: 5174,
        proxy: {
            '/api': {
                target: 'http://localhost:8089',
                changeOrigin: true
            }
        }
    },
    build: {
        // 直接产出到后端托管的静态目录，源码改动即刻生效，不用手工拷 dist。
        outDir: '../backend/src/main/resources/static',
        emptyOutDir: true,
        chunkSizeWarningLimit: 1500
    },
    test: {
        environment: 'jsdom',
        include: ['src/**/*.test.js']
    }
})
