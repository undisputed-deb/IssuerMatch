import { expect, test } from '@playwright/test';
import { createAccount, noOverflow, person as openPerson } from './helpers.js';

// The whole two-sided flow in real browsers. Creates REAL users; global-teardown.js deletes them. Runs only when asked:
// scripts/e2e.sh offline (no model calls), scripts/e2e.sh accounts (live), or by hand:
//   SIGNUP_E2E=1 SUPABASE_URL=... SUPABASE_ADMIN_KEY=... ADMIN_EMAIL=... ADMIN_PASSWORD=... npx playwright test deal
const shots = process.env.SHOTS_DIR || 'test-results/shots';

test.skip(!process.env.SIGNUP_E2E, 'creates real accounts: set SIGNUP_E2E=1 to run');

const errors = [];
const person = (browser) => openPerson(browser, errors);

test('profile, need, approval, question, answer, offer and pass', async ({ browser }) => {
  test.setTimeout(240_000);
  const stamp = Date.now();
  const firm = `E2E Deal Fund ${stamp}`;
  const title = `E2E deal ${stamp}`;
  const huge = '900000000000'; // only this investor passes the need's capital deal breaker, so the agent must rank it

  const investor = await person(browser);
  await createAccount(investor, 'Investor', 'deal-investor');
  await investor.getByLabel('Your name').fill('Ivy Chen');
  await investor.getByLabel('Your role', { exact: true }).fill('Managing Partner');
  await investor.getByLabel('Firm name').fill(firm);
  await investor.getByLabel('Deployable capital, USD').fill(huge);
  await investor.getByLabel('What you invest in, and what you do not').fill('We back European fintech with recurring revenue. No gambling.');
  await investor.getByLabel('Smallest ticket (USD)').fill('100000');
  await investor.getByLabel('Largest ticket (USD)').fill('2000000');
  await investor.getByRole('button', { name: 'Save profile' }).click();
  await expect(investor.getByRole('link', { name: 'Matched deals' })).toBeVisible();

  const issuer = await person(browser);
  await createAccount(issuer, 'Issuer', 'deal-issuer');
  await issuer.getByLabel('Company name').fill(`E2E Deal Co ${stamp}`);
  await issuer.getByRole('button', { name: 'Add company' }).click();
  await expect(issuer.getByRole('heading', { name: 'My funding needs' })).toBeVisible();
  await issuer.getByRole('link', { name: 'Submit a need' }).click();
  await issuer.getByLabel('Title').fill(title);
  await issuer.getByLabel('Description').fill('Series A raise for a European payments company with recurring revenue.');
  await issuer.getByLabel('Amount', { exact: true }).fill('500000');
  await issuer.getByLabel(/^Instrument/).selectOption('EQUITY');
  await issuer.getByLabel(/^Sector/).selectOption('FINTECH');
  await issuer.getByLabel(/^Region/).selectOption('EUROPE');
  await issuer.getByLabel(/^Risk level/).selectOption('MEDIUM');
  await issuer.getByLabel('Minimum deployable capital of an investor (USD)').fill('800000000000');
  await issuer.getByRole('button', { name: 'Submit and match' }).click();
  await expect(issuer.getByRole('status').filter({ hasText: 'Submitted' })).toBeVisible();

  const staff = await person(browser);
  await staff.goto('/');
  await staff.getByLabel('Email').fill(process.env.ADMIN_EMAIL);
  await staff.getByLabel('Password').fill(process.env.ADMIN_PASSWORD);
  await staff.locator('form').getByRole('button', { name: 'Sign in' }).click();
  const row = staff.getByRole('row').filter({ hasText: title });
  await expect(row.getByText('Needs review')).toBeVisible({ timeout: 120_000 });
  await row.getByRole('link', { name: title }).click();
  await expect(staff.getByText(firm, { exact: true })).toBeVisible();
  await staff.getByRole('button', { name: 'Approve ranking' }).click();
  await staff.getByRole('button', { name: 'Yes, approve' }).click();
  await expect(staff.getByRole('heading', { name: 'Investor offers' })).toBeVisible();

  // investor: one-click suggested question, own question, offer
  await investor.getByRole('link', { name: 'Matched deals' }).click();
  await investor.reload();
  const deal = investor.getByRole('listitem').filter({ has: investor.getByRole('heading', { name: title }) });
  await expect(deal).toBeVisible({ timeout: 30_000 });
  const suggested = await deal.getByRole('region', { name: 'Suggested diligence questions' }).getByRole('listitem').locator('span').allTextContents();
  expect(suggested).toHaveLength(3);
  console.log(`suggested questions: ${JSON.stringify(suggested)}`);
  await deal.getByRole('button', { name: 'Ask issuer' }).first().click();
  await expect(deal.getByRole('button', { name: 'Asked' })).toHaveCount(1);
  await deal.getByLabel('Ask your own question').fill('<b>How long is your runway?</b>');
  await deal.getByRole('button', { name: 'Send question' }).click();
  await expect(deal.getByText('<b>How long is your runway?</b>')).toBeVisible(); // markup stays text

  await deal.getByRole('button', { name: 'Match & Submit Offer' }).click();
  const drawer = investor.getByRole('dialog');
  await drawer.getByLabel('Amount (USD)').fill('250000');
  await drawer.getByLabel('Stake you want (%)').fill('8.5');
  await drawer.getByLabel('Valuation (USD)').fill('4000000');
  await drawer.getByLabel('Why you like this company').fill('Recurring revenue and a market we know.');
  await investor.screenshot({ path: `${shots}/offer-drawer.png` });
  await drawer.getByRole('button', { name: 'Submit offer' }).click();
  await expect(drawer.getByRole('status')).toContainText(/Offer (accepted|received)/);
  await investor.keyboard.press('Escape');
  await expect(drawer).toHaveCount(0);
  await expect(deal.getByText('Stake 8.5%', { exact: false })).toBeVisible();
  await expect(deal.getByRole('button', { name: 'Pass', exact: true })).toHaveCount(0); // no passing on a live offer
  await noOverflow(investor, 'investor-feed');

  // issuer: sees the offer, answers
  await issuer.getByRole('link', { name: 'My dashboard' }).click();
  const need = issuer.getByRole('listitem').filter({ hasText: title }).first();
  await need.getByText('Offers and questions').click();
  await expect(need.getByText(firm).first()).toBeVisible();
  await expect(need.getByText('Managing Partner', { exact: false })).toBeVisible();
  await expect(need.getByText('Recurring revenue and a market we know.')).toBeVisible();
  const answerBox = need.getByLabel(/Your answer to: <b>How long/);
  await answerBox.fill('Eighteen months.');
  await answerBox.locator('xpath=ancestor::form').getByRole('button', { name: 'Send answer' }).click();
  await expect(need.getByText('Eighteen months.')).toBeVisible();
  await noOverflow(issuer, 'issuer-dashboard');

  await investor.reload();
  await expect(investor.getByText('Eighteen months.')).toBeVisible();

  // staff: the same offer and thread, read only
  await staff.reload();
  await expect(staff.getByText('Recurring revenue and a market we know.')).toBeVisible();
  await expect(staff.getByText('Eighteen months.')).toBeVisible();
  await expect(staff.getByRole('button', { name: 'Send answer' })).toHaveCount(0);
  await noOverflow(staff, 'staff-run');

  expect(errors).toEqual([]);
});
