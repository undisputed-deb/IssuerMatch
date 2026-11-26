import { useState } from 'react';
import { api } from '../api.js';
import { Thread, terms as offerTerms } from '../deal.jsx';
import OfferDrawer from '../OfferDrawer.jsx';
import { Breakdown } from '../score.jsx';
import { Button, ErrorBox, Field, Loading, PageTitle, Status, control, money, useLoad, when, words } from '../ui.jsx';

const TERM_LABELS = {
  targetValuation: 'Target valuation', arrUsd: 'ARR (USD)', yoyGrowthPct: 'Growth % (year on year)', boardSeat: 'Board seat offered',
  tenorMonths: 'Tenor (months)', seniority: 'Seniority', collateralType: 'Collateral', targetYieldPct: 'Target yield %', ebitdaUsd: 'EBITDA (USD)',
  valuationCap: 'Valuation cap', discountPct: 'Discount %', interestRatePct: 'Interest %',
};

const show = (v) => (typeof v === 'boolean' ? (v ? 'Yes' : 'No') : typeof v === 'number' ? money(v) : words(String(v)));

/** The deals a human approved for this investor. Everything here is text; nothing is rendered as HTML. */
export default function InvestorDeals() {
  const deals = useLoad(api.investorMatches, [], () => 15000);
  const offers = useLoad(api.investorOffers, [], () => 15000);
  const reload = () => Promise.all([deals.reload(), offers.reload()]);

  return (
    <>
      <PageTitle title="Matched deals">{deals.data && `${deals.data.length} approved for your firm`}</PageTitle>
      <ErrorBox error={deals.error || offers.error} onRetry={reload} />
      {deals.loading && <Loading />}
      {deals.data?.length === 0 && <p>No deals yet. A deal appears here after our review team approves a match with your firm.</p>}
      <ul className="space-y-8">
        {deals.data?.map((d) => (
          <Deal key={d.matchResultId} deal={d} onChange={reload}
            offers={offers.data?.filter((o) => o.needId === d.needId) ?? []} />
        ))}
      </ul>
    </>
  );
}

function Deal({ deal, offers, onChange }) {
  const [drawer, setDrawer] = useState(false);
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
          <Thread needId={deal.needId} mode="investor" suggestions={deal.suggestedDiligenceQuestions} />
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
        <aside aria-label="Match score and your decision" className="space-y-6">
          <div>
            <p className="mb-2 font-mono text-3xl">{Number(deal.score).toFixed(1)}<span className="text-base text-soft"> / 100 fit</span></p>
            <Breakdown breakdown={deal.scoreBreakdown} />
          </div>
          <Decision deal={deal} offers={offers} onOffer={() => setDrawer(true)} onChange={onChange} />
        </aside>
      </div>
      {drawer && <OfferDrawer deal={deal} investorId={deal.investorId} onClose={() => setDrawer(false)} onSent={onChange} />}
    </li>
  );
}

/** Your offers on this deal, then the two choices: make an offer, or pass with an optional reason. */
function Decision({ deal, offers, onOffer, onChange }) {
  const [passing, setPassing] = useState(false);
  const [reason, setReason] = useState('');
  const [state, setState] = useState({ busy: false, error: null });
  const live = offers.some((o) => o.status !== 'REJECTED');
  const passed = deal.investorDecision === 'PASSED';

  async function pass(event) {
    event.preventDefault();
    setState({ busy: true, error: null });
    try {
      await api.pass(deal.matchResultId, reason);
      setPassing(false);
      setState({ busy: false, error: null });
    } catch (error) {
      setState({ busy: false, error });
    }
    await onChange();
  }

  return (
    <section aria-label="Your decision" className="space-y-3 border-t-2 border-ink pt-3">
      {offers.length > 0 && (
        <ul className="divide-y divide-rule border-b border-rule text-sm">
          {offers.map((o) => (
            <li key={o.commitmentId} className="space-y-1 py-2">
              <p className="flex items-center justify-between gap-2"><span className="font-mono">{money(o.amount)} {o.currency}</span><Status value={o.status} /></p>
              <p className="break-words text-soft">{offerTerms(o)}</p>
            </li>
          ))}
        </ul>
      )}
      {passed && <p role="status" className="text-sm"><b>You passed on this deal.</b>{deal.passReason && ` Reason: ${deal.passReason}`}</p>}
      <div className="flex flex-wrap gap-3">
        <Button onClick={onOffer}>{live ? 'Submit another offer' : 'Match & Submit Offer'}</Button>
        {!live && !passed && !passing && <Button tone="plain" onClick={() => setPassing(true)}>Pass</Button>}
      </div>
      {passing && !passed && (
        <form onSubmit={pass} className="space-y-2">
          <Field label="Why are you passing? (optional)">
            <textarea className={`${control} py-2`} rows={2} maxLength={1000} value={reason} onChange={(e) => setReason(e.target.value)} />
          </Field>
          <div className="flex flex-wrap gap-3">
            <Button type="submit" tone="danger" busy={state.busy}>Confirm pass</Button>
            <Button tone="plain" onClick={() => setPassing(false)}>Cancel</Button>
          </div>
        </form>
      )}
      <ErrorBox error={state.error} />
    </section>
  );
}
