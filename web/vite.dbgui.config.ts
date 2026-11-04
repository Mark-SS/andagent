import { defineConfig } from 'vite';
import { resolve } from 'node:path';
export default defineConfig({
  base: './',
  publicDir: false,
  build: {
    outDir: '../app/src/main/assets',
    emptyOutDir: false,
    rollupOptions: { input: resolve(__dirname, 'dbgui/index.html') },
    assetsDir: 'dbgui/assets',
    target: 'es2020',
  },
});
