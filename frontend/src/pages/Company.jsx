import { useState } from 'react';
import { api } from '../api.js';
import {
  Button, ErrorBox, Field, Input, Loading, PageTitle, REGIONS, SECTORS, Section, Select, TEAM_SIZES, Verified, control, number,
  useLoad, words,
} from '../ui.jsx';
import Needs from './Needs.jsx';

const PROFILE = {
  contactName: '', contactRole: '', websiteUrl: '', companySummary: '', foundedYear: '', teamSizeBand: '', totalRaisedUsd: '',
  runwayMonths: '', dataRoomUrl: '',
};
const NUMBERS = ['foundedYear', 'totalRaisedUsd', 'runwayMonths'];
const EMPTY = { name: '', sector: 'FINTECH', region: 'EUROPE', ...PROFILE };

const toForm = (c) => ({
  name: c.name, sector: c.sector, region: c.region,
  ...Object.fromEntries(Object.keys(PROFILE).map((k) => [k, c.profile[k] ?? ''])),
});

/** The server wants numbers or null, and null rather than empty text. */
const toBody = ({ name, sector, region, ...profile }) => ({
  name, sector, region,
  profile: Object.fromEntries(Object.entries(profile).map(([k, v]) => [k, NUMBERS.includes(k) ? number(v) : v || null])),
});

/** Issuer home: my companies and my funding needs. */
export default function Company() {
  const companies = useLoad(api.issuers, []);
  const [editing, setEditing] = useState(null); // null: closed, 'new', or a company id
  const items = companies.data?.items;
  const open = editing ?? (items?.length === 0 ? 'new' : null);

  async function saved() {
    await companies.reload();
    setEditing(null);
  }

  return (
    <>
      <PageTitle title="My companies">Investors matched to a raise see its company profile.</PageTitle>
      <ErrorBox error={companies.error} onRetry={companies.reload} />
      {companies.loading && <Loading />}
      {items?.length > 0 && (
        <ul className="mb-4 divide-y divide-rule border-y border-rule bg-sheet">
          {items.map((c) => (
            <li key={c.id} className="px-4 py-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{c.name}</span>
                  <Verified when={c.verified} />
                  <span className="text-sm text-soft">{words(c.sector)} · {words(c.region)}</span>
                </p>
                {open !== c.id && <Button tone="plain" onClick={() => setEditing(c.id)} aria-label={`Edit ${c.name}`}>Edit profile</Button>}
              </div>
              {open === c.id && <CompanyForm company={c} onSaved={saved} onCancel={() => setEditing(null)} />}
            </li>
          ))}
        </ul>
      )}
      {open === 'new' && <CompanyForm onSaved={saved} onCancel={items?.length ? () => setEditing(null) : null} />}
      {items?.length > 0 && open !== 'new' && <Button tone="plain" onClick={() => setEditing('new')}>Add another company</Button>}
      {items?.length > 0 && (
        <div className="mt-10">
          <Needs title="My funding needs" canRematch deals="issuer" empty="Nothing submitted yet." />
        </div>
      )}
    </>
  );
}

/** Create a company, or edit one when `company` is given. */
function CompanyForm({ company, onSaved, onCancel }) {
  const [form, setForm] = useState(() => (company ? toForm(company) : EMPTY));
  const [state, setState] = useState({ busy: false, error: null });
  const set = (name) => (e) => setForm({ ...form, [name]: e.target.value });
  const input = (name, label, props = {}) => <Input label={label} value={form[name]} onChange={set(name)} {...props} />;

  async function save(event) {
    event.preventDefault();
    setState({ busy: true, error: null });
    try {
      await (company ? api.updateIssuer(company.id, toBody(form)) : api.createIssuer(toBody(form)));
      await onSaved();
    } catch (error) {
      setState({ busy: false, error });
    }
  }

  return (
    <form onSubmit={save} className="my-4 grid max-w-3xl gap-4 sm:grid-cols-2">
      {input('name', 'Company name', { required: true, maxLength: 200, wide: true })}
      <Select label="Sector" value={form.sector} onChange={set('sector')} options={SECTORS} />
      <Select label="Region" value={form.region} onChange={set('region')} options={REGIONS} />
      <Section title="Shown to matched investors (all optional)">
        <Field label="What the company does" wide>
          <textarea className={`${control} py-2`} rows={3} maxLength={2000} value={form.companySummary} onChange={set('companySummary')} />
        </Field>
        {input('contactName', 'Contact name', { maxLength: 100 })}
        {input('contactRole', 'Contact role', { maxLength: 100, placeholder: 'Chief executive' })}
        {input('websiteUrl', 'Website', { type: 'url', maxLength: 300, placeholder: 'https://' })}
        {input('foundedYear', 'Year founded', { type: 'number', min: 1800, max: new Date().getFullYear(), step: 1 })}
        <Field label="Team size">
          <select className={control} value={form.teamSizeBand} onChange={set('teamSizeBand')}>
            <option value="">Not said</option>
            {TEAM_SIZES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </Field>
        {input('totalRaisedUsd', 'Raised so far, USD', { type: 'number', min: 0, step: 'any' })}
      </Section>
      <Section title="Private: you and our review staff only">
        {input('runwayMonths', 'Runway, months', { type: 'number', min: 0, max: 600, step: 1 })}
        {input('dataRoomUrl', 'Data room link', { type: 'url', maxLength: 500, placeholder: 'https://' })}
      </Section>
      <div className="sm:col-span-2">
        <ErrorBox error={state.error} />
        <div className="flex flex-wrap gap-3">
          <Button type="submit" busy={state.busy}>{company ? 'Save changes' : 'Add company'}</Button>
          {onCancel && <Button tone="plain" disabled={state.busy} onClick={onCancel}>Cancel</Button>}
        </div>
      </div>
    </form>
  );
}
