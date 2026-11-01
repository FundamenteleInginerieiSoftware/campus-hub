import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  server: {
    // Every /api call goes to the Spring Boot backend on :8080, so the browser sees one origin
    // and we need no CORS settings (ADR-002). In prod, Spring serves the built files itself.
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
