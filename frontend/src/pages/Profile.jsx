import { useEffect, useState } from 'react';
import { api } from '../api.js';
import {
  Button, CURRENCIES, ErrorBox, Field, INSTRUMENTS, INVESTOR_TYPES, Input, PARTICIPATION_ROLES, PageTitle, REGIONS, RISKS, SECTORS,
  STAGES, Section, Select, Verified, control, number, words,
} from '../ui.jsx';

const MANDATE = {
  sector: 'FINTECH', region: 'EUROPE', minTicket: '', maxTicket: '', riskLevel: 'MEDIUM', instruments: ['EQUITY'], targetStages: [],
};
const EMPTY = {
  contactName: '', roleTitle: '', companyName: '', websiteUrl: '', investorType: 'VC', region: 'EUROPE', aumOrRevenueUsd: '',
  deployableCapitalUsd: '', currency: 'USD', longThesisDescription: '', participationRole: '', deploymentStatus: 'ACTIVE',
  mandates: [MANDATE],
};

const toForm = (p) =>
  Object.fromEntries(Object.entries(EMPTY).map(([k, empty]) => [k, k === 'mandates' ? (p.mandates.length ? p.mandates : empty) : p[k] ?? empty]));

/** The investor's institutional profile. `profile` is null on first sign-in. */
export default function Profile({ profile, onSaved }) {
  const [form, setForm] = useState(EMPTY);
  const [state, setState] = useState({ busy: false, error: null, saved: false });
  useEffect(() => {
    if (profile) setForm(toForm(profile));
  }, [profile]);
  const set = (name) => (e) => setForm({ ...form, [name]: e.target.value });
  const setMandate = (i, change) => setForm({ ...form, mandates: form.mandates.map((m, j) => (j === i ? { ...m, ...change } : m)) });
  const input = (name, label, props = {}) => <Input label={label} value={form[name]} onChange={set(name)} {...props} />;

  async function save(event) {
    event.preventDefault();
    setState({ busy: true, error: null, saved: false });
    try {
      const saved = await api.saveProfile({
        ...form,
        websiteUrl: form.websiteUrl || null,
        participationRole: form.participationRole || null,
        aumOrRevenueUsd: number(form.aumOrRevenueUsd),
        deployableCapitalUsd: number(form.deployableCapitalUsd),
        mandates: form.mandates.map(({ sector, region, minTicket, maxTicket, riskLevel, instruments, targetStages }) =>
          ({ sector, region, minTicket: number(minTicket), maxTicket: number(maxTicket), riskLevel, instruments, targetStages })),
      });
      setState({ busy: false, error: null, saved: true });
      onSaved(saved);
    } catch (error) {
      setState({ busy: false, error, saved: false });
    }
  }

  return (
    <>
      <PageTitle title={profile?.profileCompleted ? 'My investor profile' : 'Set up your investor profile'}>
        <span className="flex items-center gap-2">
          <Verified when={profile?.verified} />
          {profile?.profileCompleted ? 'Matching uses this profile.' : 'Complete it before deals can be matched to you.'}
        </span>
      </PageTitle>
      {state.saved && <p role="status" className="mb-4 border-l-4 border-good bg-good-bg px-4 py-3 text-good">Profile saved.</p>}
      <form onSubmit={save} className="grid max-w-4xl gap-6 sm:grid-cols-2">
        <Section title="You and your firm">
          {input('contactName', 'Your name', { required: true, maxLength: 100, autoComplete: 'name' })}
          {input('roleTitle', 'Your role', { required: true, maxLength: 100, placeholder: 'Managing Partner' })}
          {input('companyName', 'Firm name', { required: true, maxLength: 200 })}
          {input('websiteUrl', 'Website (optional)', { type: 'url', maxLength: 300, placeholder: 'https://' })}
          <Select label="Investor type" value={form.investorType} onChange={set('investorType')} options={INVESTOR_TYPES} />
          <Select label="Home region" value={form.region} onChange={set('region')} options={REGIONS} />
        </Section>
        <Section title="Deal participation">
          <Field label="Your usual role in a round">
            <select className={control} value={form.participationRole} onChange={set('participationRole')}>
              <option value="">Not said</option>
              {PARTICIPATION_ROLES.map((r) => <option key={r} value={r}>{words(r)}</option>)}
            </select>
          </Field>
          <label className="flex min-h-11 cursor-pointer items-start gap-3 self-end">
            <input type="checkbox" role="switch" className="mt-1 size-5" checked={form.deploymentStatus === 'ACTIVE'}
              onChange={(e) => setForm({ ...form, deploymentStatus: e.target.checked ? 'ACTIVE' : 'PAUSED' })} />
            <span>
              <span className="text-sm font-medium">Actively deploying capital</span>
              <span className="block text-xs text-soft">
                {form.deploymentStatus === 'ACTIVE' ? 'New deals can be matched to you.' : 'Paused: no new deals are matched to you.'}
              </span>
            </span>
          </label>
        </Section>
        <Section title="Capital">
          {input('aumOrRevenueUsd', 'Assets under management or revenue, USD (optional)', { type: 'number', min: 0, step: 'any' })}
          {input('deployableCapitalUsd', 'Deployable capital, USD', { type: 'number', min: 1, step: 'any', required: true })}
          <Select label="Ticket currency" value={form.currency} onChange={set('currency')} options={CURRENCIES} />
        </Section>
        <Section title="Investment thesis">
          <Field label="What you invest in, and what you do not" wide>
            <textarea className={`${control} min-h-48 py-2`} required maxLength={10000} value={form.longThesisDescription}
              onChange={set('longThesisDescription')} aria-describedby="thesis-help" />
            <span id="thesis-help" className="text-xs text-soft">
              Companies you back, traction you require, how you add value, exclusions and portfolio highlights.{' '}
              <span className="font-mono">{form.longThesisDescription.length} / 10000</span>
            </span>
          </Field>
        </Section>
        <Section title="Mandates">
          {form.mandates.map((m, i) => (
            <div key={i} className="grid gap-3 border border-rule bg-sheet p-3 sm:col-span-2 sm:grid-cols-3">
              <Select label="Sector" value={m.sector} onChange={(e) => setMandate(i, { sector: e.target.value })} options={SECTORS} />
              <Select label="Region" value={m.region} onChange={(e) => setMandate(i, { region: e.target.value })} options={REGIONS} />
              <Select label="Risk" value={m.riskLevel} onChange={(e) => setMandate(i, { riskLevel: e.target.value })} options={RISKS} />
              <Input label={`Smallest ticket (${form.currency})`} type="number" required min="0.01" step="any" value={m.minTicket}
                onChange={(e) => setMandate(i, { minTicket: e.target.value })} />
              <Input label={`Largest ticket (${form.currency})`} type="number" required min="0.01" step="any" value={m.maxTicket}
                onChange={(e) => setMandate(i, { maxTicket: e.target.value })} />
              <fieldset>
                <legend className="text-sm font-medium">Instruments</legend>
                <div className="flex flex-wrap gap-x-3">
                  {INSTRUMENTS.map((x) => (
                    <label key={x} className="flex min-h-11 cursor-pointer items-center gap-1 text-sm">
                      <input type="checkbox" checked={m.instruments.includes(x)}
                        onChange={(e) => setMandate(i, { instruments: e.target.checked ? [...m.instruments, x] : m.instruments.filter((v) => v !== x) })} />
                      {words(x)}
                    </label>
                  ))}
                </div>
              </fieldset>
              <fieldset className="sm:col-span-3">
                <legend className="text-sm font-medium">Company stages (none ticked: not said)</legend>
                <div className="flex flex-wrap gap-x-4">
                  {STAGES.map((x) => (
                    <label key={x} className="flex min-h-11 cursor-pointer items-center gap-1 text-sm">
                      <input type="checkbox" checked={m.targetStages.includes(x)}
                        onChange={(e) => setMandate(i, { targetStages: e.target.checked ? [...m.targetStages, x] : m.targetStages.filter((v) => v !== x) })} />
                      {words(x)}
                    </label>
                  ))}
                </div>
              </fieldset>
              {form.mandates.length > 1 && (
                <button type="button" className="min-h-11 justify-self-start text-sm text-bad underline"
                  onClick={() => setForm({ ...form, mandates: form.mandates.filter((_, j) => j !== i) })}>Remove mandate {i + 1}</button>
              )}
            </div>
          ))}
          {form.mandates.length < 10 && (
            <Button tone="plain" onClick={() => setForm({ ...form, mandates: [...form.mandates, MANDATE] })}>Add another mandate</Button>
          )}
        </Section>
        <div className="sm:col-span-2">
          <ErrorBox error={state.error} />
          <Button type="submit" busy={state.busy}>Save profile</Button>
        </div>
      </form>
    </>
  );
}
