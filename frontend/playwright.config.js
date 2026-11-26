import { defineConfig } from '@playwright/test';

// Browser smoke test against a running stack. Start it with scripts/e2e.sh from the repo root.
const web = process.env.WEB_URL || 'http://localhost:5173';

export default defineConfig({
  testDir: './e2e',
  globalTeardown: './e2e/global-teardown.js',
  timeout: 180_000,
  expect: { timeout: 15_000 },
  use: { baseURL: web, screenshot: 'only-on-failure' },
  webServer: { command: `npm run dev -- --port ${new URL(web).port || 5173}`, url: web, reuseExistingServer: true },
  reporter: [['list']],
});
