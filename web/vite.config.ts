import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      // Proxy only the real backend admin API paths — NOT the SPA route /admin itself.
      // Without this restriction a browser refresh of /admin hits the backend directly.
      '^/admin/(sessions|config|audit|pool)': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
    // SPA history fallback: serve index.html for any path not matched by a file or proxy rule
    historyApiFallback: true,
  },
})