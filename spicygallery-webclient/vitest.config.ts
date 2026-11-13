import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import { quasar, transformAssetUrls } from '@quasar/vite-plugin';
import path from 'node:path';

export default defineConfig({
  plugins: [vue({ template: { transformAssetUrls } }), quasar()],
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.test.ts'],
  },
  resolve: {
    extensions: ['.mjs', '.js', '.mts', '.ts', '.jsx', '.tsx', '.json', '.vue'],
    alias: {
      src: path.resolve(__dirname, './src'),
      boot: path.resolve(__dirname, './src/boot'),
    },
  },
});
