// Playwright runs this once after all tests, passed or failed: remove every account and row the tests created.
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export default function cleanUp() {
  const script = fileURLToPath(new URL('../../scripts/e2e-cleanup.sh', import.meta.url));
  execFileSync('sh', [script], { stdio: 'inherit' }); // reads E2E_DB_SERVICE and the Supabase keys from the environment
}
