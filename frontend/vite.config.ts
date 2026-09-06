import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
        // Spring's WebSocket handler enforces same-origin handshakes. Rewrite
        // only in the local Vite proxy; production remains same-origin.
        rewriteWsOrigin: true,
      },
    },
  },
})
