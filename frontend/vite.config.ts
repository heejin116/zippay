import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      // 브라우저는 항상 같은 오리진(localhost:5173)의 /api로 요청하고, Vite가 백엔드로 넘긴다.
      // → CORS 설정이 필요 없고, HttpOnly 인증 쿠키도 5173 오리진에 그대로 저장·전송된다.
      '/api': 'http://localhost:8080',
    },
  },
})
