import { defineConfig, devices } from '@playwright/test';
import { readFileSync } from 'node:fs';
const info = JSON.parse(
  readFileSync(
    process.env.NATURAL_FIXTURE_INFO ?? '../recsys-rec-engine/target/natural-browser-fixture.json',
    'utf8',
  ),
) as { url: string };
export default defineConfig({
  testDir: './e2e-sandbox',
  outputDir: '../recsys-rec-engine/target/natural-browser-evidence',
  testMatch: 'natural-exposure.spec.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  retries: 0,
  use: {
    baseURL: 'http://127.0.0.1:4184',
    viewport: { width: 1280, height: 900 },
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } },
    },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4184 --strictPort',
    url: 'http://127.0.0.1:4184/login',
    reuseExistingServer: false,
    env: {
      VITE_AUTH_MODE: 'legacy',
      VITE_NATURAL_EXPOSURE_SANDBOX_ENABLED: 'true',
      VITE_SANDBOX_EXPOSURE_ENABLED: 'false',
      RECSYS_GATEWAY: info.url,
    },
  },
});
