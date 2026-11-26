import { useState } from 'react';
import { signIn, signUp } from '../api.js';
import { Button, ErrorBox, Field, control } from '../ui.jsx';

const EMPTY = { fullName: '', email: '', password: '', role: 'ISSUER' };
const ROLES = [
  ['ISSUER', 'Issuer', 'My company is raising money'],
  ['INVESTOR', 'Investor', 'I invest in companies'],
];

export default function Login({ onSignedIn }) {
  const [mode, setMode] = useState('signin');
  const [form, setForm] = useState(EMPTY);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const creating = mode === 'signup';
  const set = (name) => (e) => setForm({ ...form, [name]: e.target.value });

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSignedIn(await (creating ? signUp(form) : signIn(form.email, form.password)));
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  function switchTo(next) {
    setMode(next);
    setError(null);
  }

  return (
    <main className="grid min-h-screen lg:grid-cols-[3fr_2fr]">
      <section className="flex flex-col justify-end bg-ink p-8 text-white lg:p-16">
        <p className="font-mono text-lg">Issuer<span className="text-amber-300">Match</span></p>
        <h1 className="mt-6 max-w-xl text-3xl leading-tight font-semibold sm:text-5xl">
          Ranked investors, with the evidence behind every rank.
        </h1>
        <ol className="mt-8 grid max-w-xl gap-3 border-t border-white/20 pt-6 text-white/80">
          <li><b className="font-mono text-amber-300">1</b> An issuer submits a funding need.</li>
          <li><b className="font-mono text-amber-300">2</b> An agent gathers facts with read-only tools. Java computes each score.</li>
          <li><b className="font-mono text-amber-300">3</b> A person approves. Matched investors then see the deal.</li>
        </ol>
      </section>
      <section className="flex items-center p-8 lg:p-16">
        <div className="w-full max-w-sm">
          <div role="group" aria-label="Sign in or create an account" className="mb-6 flex border-b-2 border-ink">
            {[['signin', 'Sign in'], ['signup', 'Create account']].map(([key, label]) => (
              <button key={key} type="button" aria-pressed={mode === key} onClick={() => switchTo(key)}
                className={`min-h-11 flex-1 font-medium transition-colors ${mode === key ? 'bg-ink text-white' : 'text-soft hover:text-ink'}`}>
                {label}
              </button>
            ))}
          </div>
          <form onSubmit={submit} className="space-y-4">
            <h2 className="sr-only">{creating ? 'Create account' : 'Sign in'}</h2>
            {creating && (
              <Field label="Full name">
                <input className={control} required maxLength={100} autoComplete="name" value={form.fullName} onChange={set('fullName')} />
              </Field>
            )}
            <Field label="Email">
              <input className={control} required type="email" maxLength={254} autoComplete="username" value={form.email} onChange={set('email')} />
            </Field>
            <Field label="Password">
              <input className={control} required type="password" minLength={creating ? 10 : undefined} maxLength={72}
                autoComplete={creating ? 'new-password' : 'current-password'} value={form.password} onChange={set('password')} />
              {creating && <span className="text-xs text-soft">At least 10 characters.</span>}
            </Field>
            {creating && (
              <fieldset>
                <legend className="text-sm font-medium">I am an</legend>
                <div className="mt-1 grid grid-cols-2 gap-2">
                  {ROLES.map(([value, label, hint]) => (
                    <label key={value} className={`cursor-pointer border p-3 ${form.role === value ? 'border-brand bg-sheet ring-2 ring-brand' : 'border-ink/30'}`}>
                      <input type="radio" name="role" value={value} checked={form.role === value} onChange={set('role')} className="mr-2" />
                      <span className="font-medium">{label}</span>
                      <span className="mt-1 block text-xs text-soft">{hint}</span>
                    </label>
                  ))}
                </div>
              </fieldset>
            )}
            <ErrorBox error={error} />
            <Button type="submit" busy={busy}>{creating ? 'Create my account' : 'Sign in'}</Button>
          </form>
        </div>
      </section>
    </main>
  );
}
