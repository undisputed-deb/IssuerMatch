import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Local development reads the VITE_ values from backend/.env so they are never copied into a second file.
// Vite only ever exposes variables that start with VITE_ to the browser; everything else stays server side.
const ENV_DIR = '../backend';

/** Production builds get a Content Security Policy: scripts from us only, network calls only to the API and Supabase. */
function contentSecurityPolicy(env) {
  const connect = ["'self'", env.VITE_API_URL || 'http://localhost:8080', env.VITE_SUPABASE_URL].filter(Boolean);
  const policy = [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    `connect-src ${connect.join(' ')}`,
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ');
  return {
    name: 'csp',
    apply: 'build',
    transformIndexHtml: () => [
      { tag: 'meta', attrs: { 'http-equiv': 'Content-Security-Policy', content: policy }, injectTo: 'head-prepend' },
    ],
  };
}

export default defineConfig(({ mode }) => {
  const envDir = process.env.VITE_ENV_DIR || ENV_DIR;
  const env = { ...loadEnv(mode, envDir, 'VITE_'), ...process.env };
  return {
    envDir,
    plugins: [react(), tailwindcss(), contentSecurityPolicy(env)],
    server: { port: 5173, strictPort: true },
  };
});
