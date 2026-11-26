import { api } from '../api.js';
import { Breakdown } from '../score.jsx';
import { ErrorBox, Loading, PageTitle, money, useLoad, when, words } from '../ui.jsx';

const TERM_LABELS = {
  targetValuation: 'Target valuation', arrUsd: 'ARR (USD)', yoyGrowthPct: 'Growth % (year on year)', boardSeat: 'Board seat offered',
  tenorMonths: 'Tenor (months)', seniority: 'Seniority', collateralType: 'Collateral', targetYieldPct: 'Target yield %', ebitdaUsd: 'EBITDA (USD)',
  valuationCap: 'Valuation cap', discountPct: 'Discount %', interestRatePct: 'Interest %',
};

const show = (v) => (typeof v === 'boolean' ? (v ? 'Yes' : 'No') : typeof v === 'number' ? money(v) : words(String(v)));

/** The deals a human approved for this investor. Everything here is text; nothing is rendered as HTML. */
export default function InvestorDeals() {
  const deals = useLoad(api.investorMatches, [], () => 15000);

  return (
    <>
      <PageTitle title="Matched deals">{deals.data && `${deals.data.length} approved for your firm`}</PageTitle>
      <ErrorBox error={deals.error} onRetry={deals.reload} />
      {deals.loading && <Loading />}
      {deals.data?.length === 0 && <p>No deals yet. A deal appears here after our review team approves a match with your firm.</p>}
      <ul className="space-y-8">
        {deals.data?.map((d) => <Deal key={d.matchResultId} deal={d} />)}
      </ul>
    </>
  );
}

function Deal({ deal }) {
  const terms = Object.entries(deal.instrumentTerms ?? {}).filter(([k, v]) => k !== 'type' && v != null);
  return (
    <li className="border border-rule bg-sheet">
      <header className="flex flex-wrap items-start justify-between gap-3 border-b border-rule p-4">
        <div>
          <h2 className="text-xl font-semibold">{deal.title}</h2>
          <p className="text-sm text-soft">
            {deal.issuerName} · {words(deal.sector)} · {words(deal.region)}
            {deal.companyStage && ` · ${words(deal.companyStage)}`} · approved {when(deal.approvedAt)}
          </p>
        </div>
        <div className="text-right">
          <p className="font-mono text-2xl">{money(deal.amount)} <span className="text-sm text-soft">{deal.currency}</span></p>
          <p className="text-sm text-soft">{words(deal.instrument)} · {money(deal.remainingCapacity)} still open</p>
        </div>
      </header>
      <div className="grid gap-6 p-4 lg:grid-cols-[1fr_22rem]">
        <div className="space-y-4">
          <section>
            <h3 className="font-semibold">Why it fits your thesis</h3>
            <p className="max-w-prose">{deal.dealBrief}</p>
          </section>
          <section>
            <h3 className="font-semibold">Suggested diligence questions</h3>
            <ul className="mt-1 divide-y divide-rule border-y border-rule">
              {deal.suggestedDiligenceQuestions.map((q) => <li key={q} className="py-2">{q}</li>)}
            </ul>
          </section>
          <details>
            <summary className="flex min-h-11 items-center font-medium text-brand">The issuer’s description and terms</summary>
            <p className="max-w-prose whitespace-pre-wrap break-words">{deal.description}</p>
            {deal.useOfFunds?.length > 0 && (
              <>
                <p className="mt-3 font-medium">Use of funds</p>
                <ul className="list-disc pl-5">{deal.useOfFunds.map((u) => <li key={u}>{u}</li>)}</ul>
              </>
            )}
            <dl className="mt-3 grid max-w-md grid-cols-[auto_1fr] gap-x-4 text-sm">
              {deal.minTicketSize != null && (<><dt className="text-soft">Smallest ticket</dt><dd className="text-right font-mono">{money(deal.minTicketSize)}</dd></>)}
              {deal.targetCloseDate && (<><dt className="text-soft">Target close</dt><dd className="text-right font-mono">{deal.targetCloseDate}</dd></>)}
              {terms.map(([k, v]) => (<div key={k} className="contents"><dt className="text-soft">{TERM_LABELS[k] ?? k}</dt><dd className="text-right font-mono">{show(v)}</dd></div>))}
            </dl>
          </details>
        </div>
        <aside aria-label="Match score">
          <p className="mb-2 font-mono text-3xl">{Number(deal.score).toFixed(1)}<span className="text-base text-soft"> / 100 fit</span></p>
          <Breakdown breakdown={deal.scoreBreakdown} />
        </aside>
      </div>
    </li>
  );
}
