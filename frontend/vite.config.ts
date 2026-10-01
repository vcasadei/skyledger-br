import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// O build vai direto para target/classes/static: o Spring Boot serve a tela em /
// e os testes Java da fase test já a encontram (ver design D8).
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
  server: {
    // Desenvolvimento: npm run dev em :5173, com a API em :8080 (sem CORS).
    proxy: {
      '/flights': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
  },
});
