// Pieces shared by the investor, issuer and staff views of a deal: the offers table and the Q&A thread.
// All of it is other people's text, rendered as text nodes only.
import { useState } from 'react';
import { api } from './api.js';
import {
  Button, ErrorBox, Loading, Status, Verified, Website, control, money, offerStatus, useLoad, when, words,
} from './ui.jsx';

/** "Stake 10% · Valuation 8,000,000 · ..." for whatever the investor proposed. */
export function terms(offer) {
  const t = offer.proposedTerms ?? {};
  return [
    offer.proposedStakePct != null && `Stake ${money(offer.proposedStakePct)}%`,
    t.valuation != null && `Valuation ${money(t.valuation)}`,
    t.interestRatePct != null && `Interest ${money(t.interestRatePct)}%`,
    t.tenorMonths != null && `Tenor ${t.tenorMonths} months`,
    t.conditions && `Conditions: ${t.conditions}`,
  ].filter(Boolean).join(' · ') || '—';
}

/** Offers on one funding need, for its issuer and for staff. Only the issuer (canDecide) accepts or declines. */
export function Offers({ needId, canDecide = false, onDecided }) {
  const offers = useLoad(() => api.offers(needId), [needId], () => 20000);
  return (
    <section aria-label="Investor offers">
      <h3 className="font-semibold">Investor offers</h3>
      <ErrorBox error={offers.error} onRetry={offers.reload} />
      {offers.loading && <Loading label="Loading offers" />}
      {offers.data?.length === 0 && <p className="text-sm text-soft">No offers yet.</p>}
      <ul className="mt-1 divide-y divide-rule border-y border-rule empty:hidden">
        {offers.data?.map((o) => (
          <li key={o.commitmentId} className="space-y-2 py-3">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <p className="font-mono text-lg">{money(o.amount)} {o.currency}</p>
              <Status value={offerStatus(o)} />
            </div>
            <InvestorSummary offer={o} />
            <p className="text-sm text-soft">Offered {when(o.createdAt)}</p>
            <p className="text-sm break-words"><b>Terms:</b> {terms(o)}</p>
            <p className="max-w-prose text-sm whitespace-pre-wrap break-words"><b>Why they like it:</b> {o.investmentRationale ?? 'Recorded by staff, no rationale.'}</p>
            {canDecide && offerStatus(o) === 'OFFER_AWAITING' && (
              <Decide offer={o} onDone={async () => { await offers.reload(); await onDecided?.(); }} />
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Who is offering: the firm facts a company may see. */
function InvestorSummary({ offer: o }) {
  const facts = [
    ['Contact', [o.contactName, o.contactRole].filter(Boolean).join(', ')],
    ['Assets or revenue', o.aumOrRevenueUsd != null && `${money(o.aumOrRevenueUsd)} USD`],
    ['Deployable capital', o.deployableCapitalUsd != null && `${money(o.deployableCapitalUsd)} USD`],
    ['Website', o.websiteUrl && <Website url={o.websiteUrl} />],
  ].filter(([, value]) => value);
  return (
    <section aria-label={`About ${o.investorFirm}`} className="max-w-xl border border-rule bg-paper p-3 text-sm">
      <p className="flex flex-wrap items-center gap-2">
        <span className="text-base font-medium">{o.investorFirm}</span>
        <span className="text-soft">{words(o.investorType)}</span>
        <Verified when={o.verified} />
      </p>
      <dl className="mt-1 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4">
        {facts.map(([label, value]) => (
          <div key={label} className="contents"><dt className="text-soft">{label}</dt><dd className="break-words">{value}</dd></div>
        ))}
      </dl>
    </section>
  );
}

/** Accept or decline, with a second click because the answer is final. */
function Decide({ offer, onDone }) {
  const [choice, setChoice] = useState(null);
  const [state, setState] = useState({ busy: false, error: null });

  async function send() {
    setState({ busy: true, error: null });
    try {
      await api.decideOffer(offer.commitmentId, choice);
      await onDone();
    } catch (error) {
      setState({ busy: false, error });
      await onDone(); // someone else may have changed the offer
    }
  }

  return (
    <div>
      {choice === null ? (
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => setChoice(true)}>Accept offer</Button>
          <Button tone="danger" onClick={() => setChoice(false)}>Decline offer</Button>
        </div>
      ) : (
        <div role="group" aria-label="Confirm your answer" className="flex flex-wrap items-center gap-3">
          <p className="font-medium">
            {choice ? 'Accept this offer?' : `Decline this offer? ${money(offer.amount)} ${offer.currency} opens up again.`} This is final.
          </p>
          <Button tone={choice ? 'primary' : 'danger'} busy={state.busy} onClick={send}>Yes, {choice ? 'accept' : 'decline'}</Button>
          <Button tone="plain" disabled={state.busy} onClick={() => setChoice(null)}>Cancel</Button>
        </div>
      )}
      <ErrorBox error={state.error} />
    </div>
  );
}

/** One need's Q&A, loaded by itself. For the issuer and staff pages. */
export function Thread({ needId, mode }) {
  const thread = useLoad(() => api.questions(needId), [needId], (qs) => (qs.some((q) => !q.answer) ? 20000 : 0));
  return <ThreadView needId={needId} mode={mode} thread={thread} />;
}

/**
 * The Q&A of one deal. mode "investor": own thread, can ask (suggestions are one click). mode "issuer": every thread,
 * can answer. mode "staff": read only. The server decides which threads come back. The caller loads `thread`
 * ({data, loading, error, reload}), so the investor feed can fetch every deal's questions in one request.
 */
export function ThreadView({ needId, mode, thread, suggestions = [], canAsk = true }) {
  const [draft, setDraft] = useState('');
  const [state, setState] = useState({ busy: false, error: null });
  const asked = new Set(thread.data?.map((q) => q.question));

  async function ask(question, fromDraft) {
    setState({ busy: true, error: null });
    try {
      await api.ask(needId, question);
      if (fromDraft) setDraft('');
      setState({ busy: false, error: null });
    } catch (error) {
      setState({ busy: false, error });
    }
    await thread.reload();
  }

  return (
    <div className="space-y-3">
      {suggestions.length > 0 && (
        <section aria-label="Suggested diligence questions">
          <h3 className="font-semibold">Suggested diligence questions</h3>
          <ul className="mt-1 divide-y divide-rule border-y border-rule">
            {suggestions.map((q) => (
              <li key={q} className="flex flex-wrap items-center justify-between gap-2 py-2">
                <span className="min-w-0 flex-1 basis-64">{q}</span>
                {canAsk && (
                  <Button tone="plain" disabled={state.busy || !thread.data || asked.has(q)} onClick={() => ask(q, false)}>
                    {asked.has(q) ? 'Asked' : 'Ask issuer'}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}
      <section aria-label="Questions and answers">
        <h3 className="font-semibold">{mode === 'investor' ? 'Your firm’s questions to the issuer' : 'Investor questions'}</h3>
        <ErrorBox error={thread.error} onRetry={thread.reload} />
        {thread.loading && <Loading label="Loading questions" />}
        {thread.data?.length === 0 && <p className="text-sm text-soft">No questions yet.</p>}
        <ul className="mt-1 space-y-3">
          {thread.data?.map((q) => (
            <li key={q.id} className="border-l-4 border-rule pl-3">
              <p className="text-sm text-soft">{mode === 'investor' ? 'Your firm' : q.investorFirm} · {when(q.createdAt)}</p>
              <p className="max-w-prose whitespace-pre-wrap break-words font-medium">{q.question}</p>
              {q.answer && <p className="mt-1 max-w-prose whitespace-pre-wrap break-words">{q.answer}</p>}
              {!q.answer && (mode === 'issuer'
                ? <Answer needId={needId} question={q} onSent={thread.reload} />
                : <p className="text-sm text-soft">Waiting for the issuer’s answer.</p>)}
            </li>
          ))}
        </ul>
        {mode === 'investor' && !canAsk && <p className="mt-2 text-sm text-soft">Viewers read questions. An Analyst or Managing Director asks them.</p>}
        {mode === 'investor' && canAsk && (
          <form className="mt-3 space-y-2" onSubmit={(e) => { e.preventDefault(); ask(draft, true); }}>
            <label className="block">
              <span className="text-sm font-medium">Ask your own question</span>
              <textarea className={`${control} py-2`} rows={2} required maxLength={2000} value={draft} onChange={(e) => setDraft(e.target.value)} />
            </label>
            <Button type="submit" busy={state.busy}>Send question</Button>
          </form>
        )}
        <ErrorBox error={state.error} />
      </section>
    </div>
  );
}

function Answer({ needId, question, onSent }) {
  const [text, setText] = useState('');
  const [state, setState] = useState({ busy: false, error: null });

  async function send(event) {
    event.preventDefault();
    setState({ busy: true, error: null });
    try {
      await api.answer(needId, question.id, text);
      await onSent();
    } catch (error) {
      setState({ busy: false, error });
    }
  }

  return (
    <form className="mt-2 space-y-2" onSubmit={send}>
      <label className="block">
        <span className="sr-only">Your answer to: {question.question}</span>
        <textarea className={`${control} py-2`} rows={2} required maxLength={4000} placeholder="Your answer" value={text} onChange={(e) => setText(e.target.value)} />
      </label>
      <ErrorBox error={state.error} />
      <Button type="submit" busy={state.busy}>Send answer</Button>
    </form>
  );
}
