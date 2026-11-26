import { expect, test } from '@playwright/test';
import {
  apiAs, approveRanking, completeInvestorProfile, createAccount, noOverflow, person, signInStaff, submitNeed, testAccount,
} from './helpers.js';

// A firm with two people, and the offer loop to the company's answer. Creates REAL users; global-teardown.js deletes them.
// Runs only when asked: scripts/e2e.sh offline (no model calls) or scripts/e2e.sh accounts (live).
test.skip(!process.env.SIGNUP_E2E, 'creates real accounts: set SIGNUP_E2E=1 to run');
test.describe.configure({ mode: 'serial' }); // the offer loop continues with the people and the deal of the first test

const stamp = Date.now();
const firm = `E2E Team Fund ${stamp}`;
const company = `E2E Team Co ${stamp}`;
const title = `E2E team deal ${stamp}`;
const errors = [];
let director;
let analyst;
let issuer;
let staff;

const dealCard = (page) => page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: title }) });

async function openDeals(page) {
  await page.getByRole('link', { name: 'Matched deals' }).click();
  await page.reload();
  await expect(dealCard(page)).toBeVisible({ timeout: 30_000 });
}

test('a Managing Director invites an Analyst, who joins by link, sees the deal and asks, but cannot offer', async ({ browser }) => {
  test.setTimeout(240_000);
  director = await person(browser, errors);
  await createAccount(director, 'Investor', 'team-md');
  await completeInvestorProfile(director, firm);
  issuer = await person(browser, errors);
  await createAccount(issuer, 'Issuer', 'team-issuer');
  await submitNeed(issuer, company, title);
  staff = await person(browser, errors);
  await signInStaff(staff);
  await approveRanking(staff, title, firm);

  const invited = testAccount('team-analyst');
  await director.getByRole('link', { name: 'Team' }).click();
  await director.getByLabel('Their email').fill(invited.email);
  await director.getByRole('button', { name: 'Create invite link' }).click();
  const link = await director.getByLabel('Invite link').inputValue();
  expect(link).toMatch(new RegExp(`^${new URL(director.url()).origin}/invite/[A-Za-z0-9_-]{43}$`));
  await expect(director.getByRole('listitem').filter({ hasText: invited.email })).toBeVisible(); // pending
  await noOverflow(director, 'team-director');

  analyst = await person(browser, errors);
  await analyst.goto(link);
  await expect(analyst.getByRole('heading', { name: `Join ${firm}` })).toBeVisible();
  await expect(analyst.getByText(invited.email)).toHaveCount(0); // the address is shown masked
  await noOverflow(analyst, 'invite');

  // another address is refused before any account exists
  await analyst.getByLabel('Full name').fill('E2E Analyst');
  await analyst.getByLabel('Email').fill(testAccount('team-intruder').email);
  await analyst.getByLabel('Password').fill(invited.password);
  await analyst.getByRole('button', { name: 'Create account and join' }).click();
  await expect(analyst.getByRole('alert')).toContainText('different email address');
  await analyst.getByLabel('Email').fill(invited.email);
  await analyst.getByRole('button', { name: 'Create account and join' }).click();

  // straight to the deals, no profile setup, and the token is gone from the address bar
  await expect(analyst.getByRole('heading', { name: 'Matched deals' })).toBeVisible();
  expect(new URL(analyst.url()).pathname).toBe('/');
  await expect(analyst.getByRole('banner').getByText('Analyst', { exact: true })).toBeVisible();
  const deal = dealCard(analyst);
  await expect(deal).toBeVisible();
  await deal.getByLabel('Ask your own question').fill('How concentrated is your revenue?');
  await deal.getByRole('button', { name: 'Send question' }).click();
  await expect(deal.getByText('How concentrated is your revenue?')).toBeVisible();

  // blocked on screen and by the server
  await expect(deal.getByRole('button', { name: 'Match & Submit Offer' })).toBeDisabled();
  await expect(deal.getByText('Only a Managing Director can submit an offer')).toBeVisible();
  const feed = await apiAs(analyst, '/investor/matches');
  const refused = await apiAs(analyst, '/commitments', {
    method: 'POST', headers: { 'Idempotency-Key': `e2e-${stamp}` },
    body: { needId: feed.data[0].needId, investorId: feed.data[0].investorId, amount: 200000, investmentRationale: 'Trying.' },
  });
  expect(refused.status).toBe(403);
  await noOverflow(analyst, 'feed-analyst');

  await analyst.getByRole('link', { name: 'Team' }).click();
  await expect(analyst.getByRole('listitem').filter({ hasText: '(you)' })).toContainText('Analyst');
  await expect(analyst.getByRole('button', { name: 'Create invite link' })).toHaveCount(0);
  await analyst.getByRole('link', { name: 'Firm profile' }).click();
  await expect(analyst.getByLabel('Firm name')).toBeDisabled();
  await expect(analyst.getByRole('button', { name: 'Save profile' })).toHaveCount(0);

  // the link is spent
  const latecomer = await person(browser, errors);
  await latecomer.goto(link);
  await expect(latecomer.getByRole('heading', { name: 'This invitation cannot be used' })).toBeVisible();

  // the director sees the new member and the firm's question
  await director.reload();
  await expect(director.getByRole('listitem').filter({ hasText: invited.email })).toContainText('joined');
  await openDeals(director);
  await expect(dealCard(director).getByText('How concentrated is your revenue?')).toBeVisible();
});

async function submitOffer(page, amount) {
  await dealCard(page).getByRole('button', { name: 'Match & Submit Offer' }).click();
  const drawer = page.getByRole('dialog');
  await drawer.getByLabel('Amount (USD)').fill(amount);
  await drawer.getByLabel('Why you like this company').fill('Recurring revenue and a market we know.');
  await drawer.getByRole('button', { name: 'Submit offer' }).click();
  await expect(drawer.getByRole('status')).toContainText('Offer accepted');
  await page.keyboard.press('Escape');
  await expect(drawer).toHaveCount(0);
}

/** The company opens its offers and answers the one still waiting. */
async function answerOffer(page, choice) {
  await page.getByRole('link', { name: 'My dashboard' }).click();
  await page.reload();
  const need = page.getByRole('listitem').filter({ hasText: title }).first();
  await need.getByText('Offers and questions').click();
  await expect(need.getByText('Awaiting company answer')).toBeVisible();
  await need.getByRole('button', { name: `${choice} offer` }).click();
  await need.getByRole('button', { name: `Yes, ${choice.toLowerCase()}` }).click();
  await expect(need.getByText(choice === 'Accept' ? 'Accepted by company' : 'Declined by company')).toBeVisible();
  await expect(need.getByRole('button', { name: `${choice} offer` })).toHaveCount(0); // final
  return need;
}

test('an offer is declined and its capacity reopens, then a second offer is accepted', async () => {
  test.setTimeout(180_000);
  await openDeals(director);
  await submitOffer(director, '200000');
  await openDeals(director);
  await expect(dealCard(director).getByText('300,000 still open')).toBeVisible();
  await expect(dealCard(director).getByText('Awaiting company answer')).toBeVisible();

  await answerOffer(issuer, 'Decline');
  await openDeals(director);
  await expect(dealCard(director).getByText('500,000 still open')).toBeVisible(); // released
  await expect(dealCard(director).getByText('Declined by company')).toBeVisible();
  await openDeals(analyst);
  await expect(dealCard(analyst).getByText('Declined by company')).toBeVisible(); // the whole firm sees it

  await submitOffer(director, '150000');
  const need = await answerOffer(issuer, 'Accept');
  await expect(need.getByText('Declined by company')).toBeVisible(); // the first one keeps its answer
  await noOverflow(issuer, 'issuer-offers');
  await openDeals(director);
  await expect(dealCard(director).getByText('350,000 still open')).toBeVisible(); // an accepted offer stays held
  await expect(dealCard(director).getByText('Accepted by company')).toBeVisible();
  await noOverflow(director, 'feed-decided');

  // capacity, commitments and events agree for this raise
  const needId = (await apiAs(director, '/investor/matches')).data[0].needId;
  const report = await apiAs(staff, '/admin/reconciliation/run', { method: 'POST' });
  expect(report.status).toBe(200);
  expect(report.data.mismatches.filter((m) => m.needId === needId)).toEqual([]);

  expect(errors).toEqual([]);
});
