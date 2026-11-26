import { defineConfig } from '@playwright/test';

// Browser smoke test against a running stack. Start it with scripts/e2e.sh from the repo root.
export default defineConfig({
  testDir: './e2e',
  timeout: 180_000,
  expect: { timeout: 15_000 },
  use: { baseURL: process.env.WEB_URL || 'http://localhost:5173', screenshot: 'only-on-failure' },
  webServer: { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: true },
  reporter: [['list']],
});
