import { useEffect, useRef, useState } from 'react';
import { api } from './api.js';
import { Button, ErrorBox, Field, Input, control, money, number } from './ui.jsx';

const EMPTY = { amount: '', stake: '', valuation: '', interest: '', tenor: '', conditions: '', rationale: '', offerLetter: '' };

/** "Match & Submit Offer". A native modal dialog, so focus is trapped and Escape closes it. */
export default function OfferDrawer({ deal, investorId, onClose, onSent }) {
  const dialog = useRef(null);
  const attempt = useRef({ body: null, key: null });
  const [form, setForm] = useState(EMPTY);
  const [state, setState] = useState({ busy: false, error: null, result: null });
  const set = (name) => (e) => setForm({ ...form, [name]: e.target.value });
  const equity = deal.instrument === 'EQUITY';

  useEffect(() => {
    if (!dialog.current.open) dialog.current.showModal();
  }, []);

  async function submit(event) {
    event.preventDefault();
    const proposed = Object.fromEntries(Object.entries({
      valuation: number(form.valuation),
      interestRatePct: equity ? null : number(form.interest),
      tenorMonths: equity ? null : number(form.tenor),
      conditions: form.conditions.trim() || null,
    }).filter(([, v]) => v != null));
    const offer = {
      needId: deal.needId,
      investorId,
      amount: number(form.amount),
      proposedStakePct: equity ? number(form.stake) : null,
      proposedTerms: Object.keys(proposed).length ? proposed : null,
      investmentRationale: form.rationale,
      offerLetter: form.offerLetter.trim() || null,
    };
    // Same offer sent again (say the network dropped) reuses the key, so it cannot be recorded twice.
    const body = JSON.stringify(offer);
    if (attempt.current.body !== body) attempt.current = { body, key: crypto.randomUUID() };
    setState({ busy: true, error: null, result: null });
    try {
      const result = await api.submitOffer(offer, attempt.current.key);
      setState({ busy: false, error: null, result });
      await onSent();
    } catch (error) {
      setState({ busy: false, error, result: null });
    }
  }

  return (
    <dialog ref={dialog} onClose={onClose} aria-labelledby="offer-title"
      className="m-0 ml-auto h-dvh max-h-none w-full max-w-lg overflow-y-auto border-l-4 border-ink bg-sheet p-5 text-ink backdrop:bg-ink/60">
      <div className="mb-4 flex items-start justify-between gap-3 border-b-2 border-ink pb-3">
        <div>
          <h2 id="offer-title" className="text-xl font-semibold">Match &amp; Submit Offer</h2>
          <p className="text-sm text-soft">{deal.title} · {money(deal.remainingCapacity)} {deal.currency} still open</p>
        </div>
        <Button tone="plain" onClick={() => dialog.current.close()}>Close</Button>
      </div>
      {state.result ? <Result offer={state.result} /> : (
        <form onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
          <Field label="Custom letter to the issuer (optional)" wide>
            <textarea className={`${control} py-2`} rows={3} maxLength={5000} value={form.offerLetter}
              onChange={set('offerLetter')} aria-describedby="offer-letter-help" />
            <span id="offer-letter-help" className="mt-1 block text-sm text-soft">
              Write a personal note to the company as your firm’s Managing Director.
            </span>
          </Field>
          <Input label={`Amount (${deal.currency})`} wide type="number" required min="0.01" step="0.01" value={form.amount} onChange={set('amount')} />
          {equity && <Input label="Stake you want (%)" type="number" min="0.001" max="100" step="0.001" value={form.stake} onChange={set('stake')} />}
          <Input label={`Valuation (${deal.currency})`} type="number" min="0.01" step="0.01" value={form.valuation} onChange={set('valuation')} />
          {!equity && <Input label="Interest rate (% a year)" type="number" min="0" max="100" step="0.001" value={form.interest} onChange={set('interest')} />}
          {!equity && <Input label="Tenor (months)" type="number" min="1" max="600" step="1" value={form.tenor} onChange={set('tenor')} />}
          <Field label="Conditions (optional)" wide>
            <textarea className={`${control} py-2`} rows={2} maxLength={2000} value={form.conditions} onChange={set('conditions')} />
          </Field>
          <Field label="Why you like this company" wide>
            <textarea className={`${control} py-2`} rows={5} required maxLength={5000} value={form.rationale} onChange={set('rationale')} />
          </Field>
          <p className="text-sm text-soft sm:col-span-2">The issuer sees your firm, contact, amount, terms and reason. Risk checks run before the offer is accepted.</p>
          <div className="sm:col-span-2">
            <ErrorBox error={state.error} />
            <Button type="submit" busy={state.busy}>Submit offer</Button>
          </div>
        </form>
      )}
    </dialog>
  );
}

const OUTCOME = {
  ACCEPTED: ['border-good bg-good-bg text-good', 'Offer accepted. The issuer can see it now.'],
  PENDING_REVIEW: ['border-flag bg-flag-bg text-flag', 'Offer received. A reviewer has to check it; your amount is held meanwhile.'],
  REJECTED: ['border-bad bg-bad-bg text-bad', 'The risk checks did not accept this offer. Nothing is reserved. Try a different amount or contact our team.'],
};

function Result({ offer }) {
  const [tone, text] = OUTCOME[offer.status];
  return <p role="status" className={`border-l-4 px-4 py-3 ${tone}`}>{text}</p>;
}
