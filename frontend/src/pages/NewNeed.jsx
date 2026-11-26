import { useState } from 'react';
import { api } from '../api.js';
import { Button, ErrorBox, Loading, PageTitle, useLoad, words } from '../ui.jsx';

const SECTORS = ['TECHNOLOGY', 'FINTECH', 'HEALTHCARE', 'ENERGY', 'REAL_ESTATE', 'CONSUMER', 'INDUSTRIALS'];
const REGIONS = ['NORTH_AMERICA', 'EUROPE', 'ASIA_PACIFIC', 'LATAM', 'MEA'];
const INSTRUMENTS = ['EQUITY', 'DEBT', 'CONVERTIBLE', 'MEZZANINE'];
const RISKS = ['LOW', 'MEDIUM', 'HIGH'];
const EMPTY = { issuerId: '', title: '', description: '', sector: 'FINTECH', region: 'EUROPE', amount: '', instrument: 'EQUITY', riskLevel: 'MEDIUM' };
const control = 'mt-1 min-h-11 w-full rounded-sm border border-ink/30 bg-sheet px-3';

export default function NewNeed() {
  const issuers = useLoad(api.issuers, []);
  const [form, setForm] = useState(EMPTY);
  const [state, setState] = useState({ busy: false, error: null, sent: null });
  const set = (name) => (e) => setForm({ ...form, [name]: e.target.value });

  async function submit(event) {
    event.preventDefault();
    setState({ busy: true, error: null, sent: null });
    try {
      const sent = await api.submitNeed({ ...form, issuerId: form.issuerId || issuers.data.items[0].id, amount: Number(form.amount) });
      setState({ busy: false, error: null, sent });
      setForm(EMPTY);
    } catch (error) {
      setState({ busy: false, error, sent: null });
    }
  }

  const select = (name, label, options) => (
    <label className="block">
      <span className="text-sm font-medium">{label}</span>
      <select className={control} value={form[name]} onChange={set(name)}>
        {options.map((o) => <option key={o} value={o}>{words(o)}</option>)}
      </select>
    </label>
  );

  if (issuers.loading) return <Loading label="Loading companies" />;
  if (issuers.error) return <ErrorBox error={issuers.error} onRetry={issuers.reload} />;
  if (!issuers.data.items.length) return <p>You have no company yet, so there is nothing to raise money for.</p>;

  return (
    <>
      <PageTitle title="Submit a funding need">Matching starts as soon as you submit.</PageTitle>
      {state.sent && (
        <p role="status" className="mb-4 border-l-4 border-good bg-good-bg px-4 py-3 text-good">
          Submitted “{state.sent.title}”. <a className="font-medium underline" href="#/needs">Follow its status</a>.
        </p>
      )}
      <form onSubmit={submit} className="grid max-w-3xl gap-4 sm:grid-cols-2">
        <label className="block sm:col-span-2">
          <span className="text-sm font-medium">Company</span>
          <select className={control} value={form.issuerId} onChange={set('issuerId')}>
            {issuers.data.items.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
          </select>
        </label>
        <label className="block sm:col-span-2">
          <span className="text-sm font-medium">Title</span>
          <input className={control} required maxLength={200} value={form.title} onChange={set('title')} />
        </label>
        <label className="block sm:col-span-2">
          <span className="text-sm font-medium">Description</span>
          <textarea className={`${control} min-h-32 py-2`} required maxLength={5000} value={form.description} onChange={set('description')} />
          <span className="font-mono text-xs text-soft">{form.description.length} / 5000</span>
        </label>
        <label className="block">
          <span className="text-sm font-medium">Amount</span>
          <input className={`${control} font-mono`} required type="number" min="0.01" step="0.01" inputMode="decimal" value={form.amount} onChange={set('amount')} />
        </label>
        {select('instrument', 'Instrument', INSTRUMENTS)}
        {select('sector', 'Sector', SECTORS)}
        {select('region', 'Region', REGIONS)}
        {select('riskLevel', 'Risk level', RISKS)}
        <div className="sm:col-span-2">
          <ErrorBox error={state.error} />
          <Button type="submit" busy={state.busy}>Submit and match</Button>
        </div>
      </form>
    </>
  );
}
