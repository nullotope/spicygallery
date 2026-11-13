import { defineConfig } from '#q-app/wrappers';
import path from 'path';

// SpicyGallery SPA (mirrored): history mode, dist/spa output, dev proxy
// to the API with SSE buffering disabled.
export default defineConfig(function () {
  return {
    boot: ['pinia', 'axios'],

    css: ['app.scss'],

    extras: ['material-icons'],

    build: {
      vueRouterMode: 'history',
      distDir: 'dist/spa',

      target: {
        browser: 'baseline-widely-available',
        node: 'node22',
      },

      typescript: {
        strict: true,
        vueShim: true,
      },

      extendViteConf() {
        // any Vite config overrides
      },
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },

    devServer: {
      open: false,
      port: 9000,
      proxy: {
        '/api': {
          // literal IPv4 — the server binds IPv4 only; avoids ::1 stalls
          target: 'http://127.0.0.1:8080',
          changeOrigin: true,
          // SSE support: Vite's proxy buffers responses by default, which breaks
          // text/event-stream; disable buffering for those responses.
          configure: (proxy) => {
            proxy.on('proxyRes', (proxyRes) => {
              if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
                proxyRes.headers['x-accel-buffering'] = 'no';
              }
            });
          },
        },
      },
    },

    framework: {
      config: {
        brand: {
          // light theme per the mockup; accent tuning can come later
        },
      },
      plugins: [],
    },
  };
});
