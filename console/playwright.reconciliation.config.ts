import { defineConfig, devices } from '@playwright/test';
import { readFileSync } from 'node:fs';
const info = JSON.parse(readFileSync(process.env.RECONCILIATION_FIXTURE_INFO!, 'utf8')) as {
  url: string;
};
export default defineConfig({
  testDir: './e2e-sandbox',
  testMatch: 'reconciliation.spec.ts',
  outputDir:
    '../recsys-ad-serving/target/reconciliation-evidence-' +
    (process.env.RECONCILIATION_MODE ?? 'grpc'),
  fullyParallel: false,
  workers: 1,
  timeout: 45000,
  retries: 0,
  projects: [
    {
      name: 'enabled',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: 'http://127.0.0.1:4187',
        viewport: { width: 1440, height: 900 },
        trace: 'off',
      },
    },
    {
      name: 'default-off',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: 'http://127.0.0.1:4188',
        viewport: { width: 1440, height: 900 },
        trace: 'off',
      },
    },
  ],
  webServer: [true, false].map((enabled) => ({
    command: `npm run dev -- --host 127.0.0.1 --port ${enabled ? 4187 : 4188} --strictPort`,
    url: `http://127.0.0.1:${enabled ? 4187 : 4188}/login`,
    reuseExistingServer: false,
    env: {
      VITE_AUTH_MODE: 'legacy',
      VITE_SANDBOX_RECONCILIATION_ENABLED: enabled ? 'true' : '',
      RECSYS_GATEWAY: info.url,
    },
  })),
});
