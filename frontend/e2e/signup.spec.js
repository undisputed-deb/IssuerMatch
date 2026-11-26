import { expect, test } from '@playwright/test';

// Creates REAL users through the public signup, then deletes them. Runs only when asked:
//   SIGNUP_E2E=1 SUPABASE_URL=... SUPABASE_ADMIN_KEY=... npx playwright test signup
const url = process.env.SUPABASE_URL;
const key = process.env.SUPABASE_ADMIN_KEY;
const shots = process.env.SHOTS_DIR || 'test-results/shots';

test.skip(!process.env.SIGNUP_E2E, 'creates real accounts: set SIGNUP_E2E=1 to run');

const created = new Set(); // emails registered by this run

test.afterAll(async ({ request }) => {
  const headers = { apikey: key, Authorization: `Bearer ${key}` };
  const list = await (await request.get(`${url}/auth/v1/admin/users?per_page=200`, { headers })).json();
  for (const user of list.users.filter((u) => created.has(u.email))) {
    const res = await request.delete(`${url}/auth/v1/admin/users/${user.id}`, { headers });
    console.log(`cleanup: deleted test user (${res.status()})`);
  }
});

async function createAccount(page, role, label) {
  const email = `e2e-${label}-${Date.now()}@issuermatch.test`;
  const password = `pw-${Math.random().toString(36).slice(2)}-${Date.now()}`;
  await page.goto('/');
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await page.getByLabel('Full name').fill(`E2E ${label}`);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('radio', { name: new RegExp(role) }).check();
  await page.screenshot({ path: `${shots}/signup-${label}.png` });
  created.add(email); // register for cleanup before the request, so a failure cannot leave the account behind
  await page.getByRole('button', { name: 'Create my account' }).click();
}

test('an investor signs up, is sent to onboarding, saves a profile and sees an empty deal feed', async ({ page }) => {
  await createAccount(page, 'Investor', 'investor');
  await expect(page.getByRole('heading', { name: 'Set up your investor profile' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Match runs' })).toHaveCount(0);
  await page.getByLabel('Your name').fill('Ivy Chen');
  await page.getByLabel('Your role').fill('Managing Partner');
  await page.getByLabel('Firm name').fill(`E2E Fund ${Date.now()}`);
  await page.getByLabel('Deployable capital, USD').fill('25000000');
  await page.getByLabel('What you invest in, and what you do not').fill('We back European fintech with at least 1M ARR. No gambling.');
  await page.getByLabel('Smallest ticket (USD)').fill('100000');
  await page.getByLabel('Largest ticket (USD)').fill('2000000');
  await page.screenshot({ path: `${shots}/investor-profile.png`, fullPage: true });
  await page.getByRole('button', { name: 'Save profile' }).click();
  await page.getByRole('link', { name: 'Matched deals' }).click();
  await expect(page.getByText('No deals yet')).toBeVisible();
  await page.screenshot({ path: `${shots}/investor-deals.png`, fullPage: true });
});

test('an issuer signs up, lands on its dashboard and adds a company', async ({ page }) => {
  await createAccount(page, 'Issuer', 'issuer');
  await expect(page.getByRole('heading', { name: 'My companies' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Match runs' })).toHaveCount(0);
  const company = `E2E Co ${Date.now()}`;
  await page.getByLabel('Company name').fill(company);
  await page.getByRole('button', { name: 'Add company' }).click();
  await expect(page.getByRole('listitem').filter({ hasText: company })).toBeVisible();
  await page.screenshot({ path: `${shots}/issuer-dashboard.png`, fullPage: true });
});
