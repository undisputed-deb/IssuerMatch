// Steps the two-sided browser tests share. Accounts made here are deleted by global-teardown.js.
import { expect } from '@playwright/test';

/** Only this investor passes a need's capital deal breaker, so a ranking is sure to include it. */
export const HUGE_CAPITAL = '900000000000';
export const CAPITAL_BAR = '800000000000';

export async function person(browser, errors) {
  const page = await (await browser.newContext()).newPage();
  page.on('pageerror', (e) => errors.push(e.message));
  return page;
}

export const testAccount = (label) => ({
  email: `e2e-${label}-${Date.now()}@issuermatch.test`,
  password: `pw-${Math.random().toString(36).slice(2)}-${Date.now()}`,
});

export async function createAccount(page, role, label) {
  const account = testAccount(label);
  await page.goto('/');
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await page.getByLabel('Full name').fill(`E2E ${label}`);
  await page.getByLabel('Email').fill(account.email);
  await page.getByLabel('Password').fill(account.password);
  await page.getByRole('radio', { name: new RegExp(role) }).check();
  await page.getByRole('button', { name: 'Create my account' }).click();
  return account;
}

export async function completeInvestorProfile(page, firm) {
  await page.getByLabel('Your name').fill('Ivy Chen');
  await page.getByLabel('Your role', { exact: true }).fill('Managing Partner');
  await page.getByLabel('Firm name').fill(firm);
  await page.getByLabel('Deployable capital, USD').fill(HUGE_CAPITAL);
  await page.getByLabel('What you invest in, and what you do not').fill('We back European fintech with recurring revenue. No gambling.');
  await page.getByLabel('Smallest ticket (USD)').fill('100000');
  await page.getByLabel('Largest ticket (USD)').fill('2000000');
  await page.getByRole('button', { name: 'Save profile' }).click();
  await expect(page.getByRole('link', { name: 'Matched deals' })).toBeVisible();
}

/** A signed-up issuer adds a company and submits an equity raise of 500,000 USD. */
export async function submitNeed(page, company, title) {
  await page.getByLabel('Company name').fill(company);
  await page.getByRole('button', { name: 'Add company' }).click();
  await expect(page.getByRole('heading', { name: 'My funding needs' })).toBeVisible();
  await page.getByRole('link', { name: 'Submit a need' }).click();
  await page.getByLabel('Title').fill(title);
  await page.getByLabel('Description').fill('Series A raise for a European payments company with recurring revenue.');
  await page.getByLabel('Amount', { exact: true }).fill('500000');
  await page.getByLabel(/^Instrument/).selectOption('EQUITY');
  await page.getByLabel(/^Sector/).selectOption('FINTECH');
  await page.getByLabel(/^Region/).selectOption('EUROPE');
  await page.getByLabel(/^Risk level/).selectOption('MEDIUM');
  await page.getByLabel('Minimum deployable capital of an investor (USD)').fill(CAPITAL_BAR);
  await page.getByRole('button', { name: 'Submit and match' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Submitted' })).toBeVisible();
}

export async function signInStaff(page) {
  await page.goto('/');
  await page.getByLabel('Email').fill(process.env.ADMIN_EMAIL);
  await page.getByLabel('Password').fill(process.env.ADMIN_PASSWORD);
  await page.locator('form').getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
}

/** Staff approve the ranking of the need with this title, which must include the firm. */
export async function approveRanking(staff, title, firm) {
  await staff.getByRole('link', { name: 'Match runs' }).click();
  const row = staff.getByRole('row').filter({ hasText: title });
  await expect(row.getByText('Needs review')).toBeVisible({ timeout: 120_000 }); // matching runs in the background
  await row.getByRole('link', { name: title }).click();
  await expect(staff.getByText(firm, { exact: true })).toBeVisible();
  await staff.getByRole('button', { name: 'Approve ranking' }).click();
  await staff.getByRole('button', { name: 'Yes, approve' }).click();
  await expect(staff.getByRole('heading', { name: 'Investor offers' })).toBeVisible();
}

/** Calls the API as whoever is signed in on this page, to check what the server (not the screen) allows. */
export function apiAs(page, path, options = {}) {
  const api = process.env.API_URL || 'http://localhost:8080';
  return page.evaluate(async ({ url, method, body, headers }) => {
    const res = await fetch(url, {
      method,
      headers: { Authorization: `Bearer ${sessionStorage.getItem('issuermatch.token')}`, 'Content-Type': 'application/json', ...headers },
      body: body && JSON.stringify(body),
    });
    return { status: res.status, data: await res.json().catch(() => null) };
  }, { url: api + path, method: options.method || 'GET', body: options.body, headers: options.headers || {} });
}

export async function noOverflow(page, name) {
  const shots = process.env.SHOTS_DIR || 'test-results/shots';
  for (const width of [375, 768, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `${name}: no horizontal scroll at ${width}px`).toBeLessThanOrEqual(0);
    await page.screenshot({ path: `${shots}/${name}-${width}.png`, fullPage: true });
  }
}
