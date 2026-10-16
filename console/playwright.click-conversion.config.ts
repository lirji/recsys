import { defineConfig, devices } from '@playwright/test';
import { readFileSync } from 'node:fs';
const info = JSON.parse(
  readFileSync(
    process.env.ACTIONS_FIXTURE_INFO ?? '../recsys-ad-serving/target/browser-fixture.json',
    'utf8',
  ),
) as { url: string };
export default defineConfig({
  testDir: './e2e-sandbox',
  outputDir: '../recsys-ad-serving/target/actions-evidence-' + (process.env.ACTIONS_MODE ?? 'grpc'),
  testMatch: 'click-conversion.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  retries: 0,
  use: {
    baseURL: 'http://127.0.0.1:4186',
    viewport: { width: 1280, height: 900 },
    trace: 'off',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } },
    },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4186 --strictPort',
    url: 'http://127.0.0.1:4186/login',
    reuseExistingServer: false,
    env: {
      VITE_AUTH_MODE: 'legacy',
      VITE_SANDBOX_CLICK_ENABLED: 'true',
      RECSYS_GATEWAY: info.url,
    },
  },
});
