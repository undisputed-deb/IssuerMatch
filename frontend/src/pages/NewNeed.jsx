import { useState } from 'react';
import { api } from '../api.js';
import {
  Button, CURRENCIES, ErrorBox, Field, INSTRUMENTS, INVESTOR_TYPES, Input, Loading, PageTitle, REGIONS, RISKS, SECTORS, STAGES,
  Section, Select, control, number, useLoad, words,
} from '../ui.jsx';

const EMPTY = {
  issuerId: '', title: '', description: '', sector: 'FINTECH', region: 'EUROPE', amount: '', currency: 'USD', instrument: 'EQUITY',
  riskLevel: 'MEDIUM', companyStage: 'SEED', targetCloseDate: '', useOfFunds: '', minTicketSize: '',
};
const NO_LIMITS = { requireInstrumentMatch: false, requireRegionMatch: false, minInvestorDeployableUsd: '', excludedInvestorTypes: [] };

/** The fields each instrument needs. Mezzanine is raised on debt terms. */
const TERMS = {
  EQUITY: ['EQUITY', [['targetValuation', 'Target valuation'], ['arrUsd', 'Annual recurring revenue (USD)'], ['yoyGrowthPct', 'Year-on-year growth %']]],
  DEBT: ['DEBT', [['tenorMonths', 'Tenor (months)'], ['targetYieldPct', 'Target yield %'], ['ebitdaUsd', 'EBITDA (USD)']]],
  MEZZANINE: ['DEBT', [['tenorMonths', 'Tenor (months)'], ['targetYieldPct', 'Target yield %'], ['ebitdaUsd', 'EBITDA (USD)']]],
  CONVERTIBLE: ['CONVERTIBLE', [['valuationCap', 'Valuation cap'], ['discountPct', 'Discount %'], ['interestRatePct', 'Interest rate %']]],
};

export default function NewNeed() {
  const issuers = useLoad(api.issuers, []);
  const [form, setForm] = useState(EMPTY);
  const [terms, setTerms] = useState({});
  const [limits, setLimits] = useState(NO_LIMITS);
  const [state, setState] = useState({ busy: false, error: null, sent: null });
  const set = (name) => (e) => setForm({ ...form, [name]: e.target.value });
  const setTerm = (name, value) => setTerms({ ...terms, [name]: value });
  const [termType, termFields] = TERMS[form.instrument];

  function instrumentTerms() {
    const body = { type: termType };
    termFields.forEach(([key]) => { body[key] = number(terms[key]); });
    if (termType === 'EQUITY') body.boardSeat = Boolean(terms.boardSeat);
    if (termType === 'DEBT') {
      body.seniority = terms.seniority || 'SENIOR_SECURED';
      body.collateralType = terms.collateralType || null;
    }
    return body;
  }

  async function submit(event) {
    event.preventDefault();
    setState({ busy: true, error: null, sent: null });
    try {
      const sent = await api.submitNeed({
        ...form,
        issuerId: form.issuerId || issuers.data.items[0].id,
        amount: number(form.amount),
        minTicketSize: number(form.minTicketSize),
        targetCloseDate: form.targetCloseDate || null,
        useOfFunds: form.useOfFunds.split('\n').map((s) => s.trim()).filter(Boolean),
        instrumentTerms: instrumentTerms(),
        hardConstraints: { ...limits, minInvestorDeployableUsd: number(limits.minInvestorDeployableUsd) },
      });
      setState({ busy: false, error: null, sent });
      setForm(EMPTY);
      setTerms({});
      setLimits(NO_LIMITS);
    } catch (error) {
      setState({ busy: false, error, sent: null });
    }
  }

  const input = (name, label, props = {}) => <Input label={label} value={form[name]} onChange={set(name)} {...props} />;
  const check = (key, label) => (
    <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm">
      <input type="checkbox" checked={limits[key]} onChange={(e) => setLimits({ ...limits, [key]: e.target.checked })} />
      {label}
    </label>
  );

  if (issuers.loading) return <Loading label="Loading companies" />;
  if (issuers.error) return <ErrorBox error={issuers.error} onRetry={issuers.reload} />;
  if (!issuers.data.items.length) {
    return <p>You have no company yet. <a className="font-medium text-brand underline" href="#/company">Add your company</a> first.</p>;
  }

  return (
    <>
      <PageTitle title="Submit a funding need">Matching starts as soon as you submit.</PageTitle>
      {state.sent && (
        <p role="status" className="mb-4 border-l-4 border-good bg-good-bg px-4 py-3 text-good">
          Submitted “{state.sent.title}”. <a className="font-medium underline" href="#/needs">Follow its status</a>.
        </p>
      )}
      <form onSubmit={submit} className="grid max-w-4xl gap-6 sm:grid-cols-2">
        <Section title="The raise">
          <Field label="Company" wide>
            <select className={control} value={form.issuerId} onChange={set('issuerId')}>
              {issuers.data.items.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
            </select>
          </Field>
          {input('title', 'Title', { required: true, maxLength: 200, wide: true })}
          <Field label="Description" wide>
            <textarea className={`${control} min-h-32 py-2`} required maxLength={5000} value={form.description} onChange={set('description')} />
            <span className="font-mono text-xs text-soft">{form.description.length} / 5000</span>
          </Field>
          <div className="grid grid-cols-[7rem_1fr] gap-2">
            <Select label="Currency" value={form.currency} onChange={set('currency')} options={CURRENCIES} />
            {input('amount', 'Amount', { type: 'number', required: true, min: '0.01', step: '0.01', inputMode: 'decimal' })}
          </div>
          {input('minTicketSize', 'Smallest ticket you accept (optional)', { type: 'number', min: '0.01', step: '0.01' })}
          <Select label="Company stage" value={form.companyStage} onChange={set('companyStage')} options={STAGES} />
          {input('targetCloseDate', 'Target close date (optional)', { type: 'date', min: new Date().toISOString().slice(0, 10) })}
          <Field label="Use of funds, one item per line" wide>
            <textarea className={`${control} min-h-24 py-2`} value={form.useOfFunds} onChange={set('useOfFunds')} />
          </Field>
          <Select label="Sector" value={form.sector} onChange={set('sector')} options={SECTORS} />
          <Select label="Region" value={form.region} onChange={set('region')} options={REGIONS} />
          <Select label="Risk level" value={form.riskLevel} onChange={set('riskLevel')} options={RISKS} />
        </Section>

        <Section title="Instrument and terms">
          <Select label="Instrument" value={form.instrument}
            onChange={(e) => { setForm({ ...form, instrument: e.target.value }); setTerms({}); }} options={INSTRUMENTS} />
          {termFields.map(([key, label]) => (
            <Input key={`${termType}-${key}`} label={`${label} (optional)`} type="number" step="any" value={terms[key] ?? ''}
              onChange={(e) => setTerm(key, e.target.value)} />
          ))}
          {termType === 'EQUITY' && (
            <label className="flex min-h-11 cursor-pointer items-center gap-2">
              <input type="checkbox" checked={Boolean(terms.boardSeat)} onChange={(e) => setTerm('boardSeat', e.target.checked)} />
              A board seat is offered
            </label>
          )}
          {termType === 'DEBT' && (
            <>
              <Select label="Seniority" value={terms.seniority || 'SENIOR_SECURED'} onChange={(e) => setTerm('seniority', e.target.value)}
                options={['SENIOR_SECURED', 'SENIOR_UNSECURED', 'SUBORDINATED', 'MEZZANINE']} />
              <Input label="Collateral (optional)" maxLength={100} value={terms.collateralType ?? ''} onChange={(e) => setTerm('collateralType', e.target.value)} />
            </>
          )}
        </Section>

        <Section title="Deal breakers (optional)">
          {check('requireInstrumentMatch', 'Only investors whose mandate includes this instrument')}
          {check('requireRegionMatch', 'Only investors with a mandate in this region')}
          <Input label="Minimum deployable capital of an investor (USD)" type="number" min="0" step="any"
            value={limits.minInvestorDeployableUsd} onChange={(e) => setLimits({ ...limits, minInvestorDeployableUsd: e.target.value })} />
          <fieldset>
            <legend className="text-sm font-medium">Exclude investor types</legend>
            <div className="flex flex-wrap gap-x-3">
              {INVESTOR_TYPES.map((t) => (
                <label key={t} className="flex min-h-11 cursor-pointer items-center gap-1 text-sm">
                  <input type="checkbox" checked={limits.excludedInvestorTypes.includes(t)}
                    onChange={(e) => setLimits({ ...limits, excludedInvestorTypes: e.target.checked
                      ? [...limits.excludedInvestorTypes, t] : limits.excludedInvestorTypes.filter((v) => v !== t) })} />
                  {words(t)}
                </label>
              ))}
            </div>
          </fieldset>
        </Section>
        <div className="sm:col-span-2">
          <ErrorBox error={state.error} />
          <Button type="submit" busy={state.busy}>Submit and match</Button>
        </div>
      </form>
    </>
  );
}
