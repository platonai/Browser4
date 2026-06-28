/**
 * Vite config for the background service worker.
 * Built separately from the UI because it needs library mode (no HTML entry).
 */

import { resolve } from 'path';
import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    outDir: resolve(__dirname, 'dist'),
    emptyOutDir: false,
    minify: false,
    lib: {
      entry: resolve(__dirname, 'src/background.ts'),
      fileName: () => 'lib/background.mjs',
      formats: ['es'],
    },
    rollupOptions: {
      external: [],
    },
  },
});
