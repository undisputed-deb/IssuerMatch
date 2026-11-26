import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { Button, ErrorBox, Field, PageTitle, REGIONS, Select, control } from '../ui.jsx';

const TYPES = ['VC', 'PE', 'FAMILY_OFFICE', 'BANK', 'ANGEL'];
const EMPTY = { name: '', type: 'VC', region: 'EUROPE' };

/** The investor's own profile. `profile` is null on first sign-in. */
export default function Profile({ profile, onSaved }) {
  const [form, setForm] = useState(EMPTY);
  const [state, setState] = useState({ busy: false, error: null, saved: false });
  useEffect(() => {
    if (profile) setForm({ name: profile.name, type: profile.type, region: profile.region });
  }, [profile]);
  const set = (name) => (e) => setForm({ ...form, [name]: e.target.value });

  async function save(event) {
    event.preventDefault();
    setState({ busy: true, error: null, saved: false });
    try {
      const saved = await api.saveProfile(form);
      setState({ busy: false, error: null, saved: true });
      onSaved(saved);
    } catch (error) {
      setState({ busy: false, error, saved: false });
    }
  }

  return (
    <>
      <PageTitle title={profile ? 'My investor profile' : 'Set up your investor profile'}>
        {profile ? 'Matching uses this profile.' : 'You need a profile before deals can be matched to you.'}
      </PageTitle>
      {state.saved && <p role="status" className="mb-4 border-l-4 border-good bg-good-bg px-4 py-3 text-good">Profile saved.</p>}
      <form onSubmit={save} className="grid max-w-3xl gap-4 sm:grid-cols-2">
        <Field label="Firm name" wide>
          <input className={control} required maxLength={200} value={form.name} onChange={set('name')} />
        </Field>
        <Select label="Investor type" value={form.type} onChange={set('type')} options={TYPES} />
        <Select label="Home region" value={form.region} onChange={set('region')} options={REGIONS} />
        <div className="sm:col-span-2">
          <ErrorBox error={state.error} />
          <Button type="submit" busy={state.busy}>Save profile</Button>
        </div>
      </form>
    </>
  );
}
