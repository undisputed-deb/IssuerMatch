import { useState } from 'react';
import { api } from '../api.js';
import { Button, ErrorBox, Field, Loading, PageTitle, REGIONS, SECTORS, Select, control, useLoad, words } from '../ui.jsx';
import Needs from './Needs.jsx';

const EMPTY = { name: '', sector: 'FINTECH', region: 'EUROPE' };

/** Issuer home: my companies and my funding needs. */
export default function Company() {
  const companies = useLoad(api.issuers, []);
  const [form, setForm] = useState(EMPTY);
  const [state, setState] = useState({ busy: false, error: null });
  const set = (name) => (e) => setForm({ ...form, [name]: e.target.value });

  async function add(event) {
    event.preventDefault();
    setState({ busy: true, error: null });
    try {
      await api.createIssuer(form);
      setForm(EMPTY);
      setState({ busy: false, error: null });
      await companies.reload();
    } catch (error) {
      setState({ busy: false, error });
    }
  }

  return (
    <>
      <PageTitle title="My companies">Only you and our review staff can see these.</PageTitle>
      <ErrorBox error={companies.error} onRetry={companies.reload} />
      {companies.loading && <Loading />}
      {companies.data?.items.length > 0 && (
        <ul className="mb-6 divide-y divide-rule border-y border-rule bg-sheet">
          {companies.data.items.map((c) => (
            <li key={c.id} className="flex flex-wrap justify-between gap-2 px-4 py-3">
              <span className="font-medium">{c.name}</span>
              <span className="text-sm text-soft">{words(c.sector)} · {words(c.region)}</span>
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={add} className="mb-10 grid max-w-3xl items-end gap-4 sm:grid-cols-2">
        <Field label="Company name" wide>
          <input className={control} required maxLength={200} value={form.name} onChange={set('name')} />
        </Field>
        <Select label="Sector" value={form.sector} onChange={set('sector')} options={SECTORS} />
        <Select label="Region" value={form.region} onChange={set('region')} options={REGIONS} />
        <div className="sm:col-span-2">
          <ErrorBox error={state.error} />
          <Button type="submit" busy={state.busy}>Add company</Button>
        </div>
      </form>
      {companies.data?.items.length > 0 && <Needs title="My funding needs" canRematch empty="Nothing submitted yet." />}
    </>
  );
}
