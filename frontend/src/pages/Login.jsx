import { useState } from 'react';
import { signIn, signUp } from '../api.js';
import { Button, ErrorBox, Field, control } from '../ui.jsx';
import './Login.css';

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
  const [focusedField, setFocusedField] = useState(null);
  const [showPassword, setShowPassword] = useState(false);
  const creating = mode === 'signup';
  const set = (name) => (e) => setForm({ ...form, [name]: e.target.value });
  const headTilt = Math.max(-7, Math.min(1, 1 - Math.max(form.email.length - 10, 0) * 0.22));

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
          <AuthAvatar focusedField={focusedField} showPassword={showPassword} headTilt={headTilt} />
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
              <input className={control} required type="email" maxLength={254} autoComplete="username" value={form.email} onChange={set('email')}
                onFocus={() => setFocusedField('email')} onBlur={() => setFocusedField(null)} />
            </Field>
            <div className="space-y-1">
              <label htmlFor="auth-password" className="block text-sm font-medium">Password</label>
              <div className="relative" onFocus={() => setFocusedField('password')}
                onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) setFocusedField(null); }}>
                <input id="auth-password" className={`${control} pr-20`} required type={showPassword ? 'text' : 'password'} minLength={creating ? 10 : undefined} maxLength={72}
                  autoComplete={creating ? 'new-password' : 'current-password'} value={form.password} onChange={set('password')}
                />
                <button type="button" aria-pressed={showPassword} aria-label={showPassword ? 'Hide password' : 'Show password'}
                  onClick={() => setShowPassword((visible) => !visible)}
                  className="absolute right-1 top-1 min-h-9 rounded-sm px-3 text-sm font-medium text-brand hover:bg-paper focus-visible:outline">
                  {showPassword ? 'Hide' : 'Show'}
                </button>
              </div>
              {creating && <span className="text-xs text-soft">At least 10 characters.</span>}
            </div>
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

function AuthAvatar({ focusedField, showPassword, headTilt }) {
  const pose = focusedField === 'password' ? (showPassword ? 'peeking' : 'covering') : '';
  return (
    <div className={`auth-avatar ${pose}`} aria-hidden="true" style={{ '--head-tilt': `${headTilt}deg` }}>
      <svg viewBox="0 0 240 176" role="presentation" focusable="false">
        <ellipse className="auth-avatar-shadow" cx="120" cy="164" rx="70" ry="8" />
        <g className="auth-avatar-head">
          <path className="auth-avatar-ear" d="M77 48C56 17 42 16 38 21c-3 4 3 35 22 51m103-24c21-31 35-32 39-27 3 4-3 35-22 51" />
          <ellipse className="auth-avatar-fur" cx="120" cy="78" rx="69" ry="57" />
          <ellipse className="auth-avatar-muzzle" cx="120" cy="99" rx="38" ry="24" />
          <g className="auth-avatar-eyes">
            <ellipse cx="96" cy="71" rx="5" ry="7" />
            <ellipse cx="144" cy="71" rx="5" ry="7" />
          </g>
          <path className="auth-avatar-nose" d="M109 91c0-5 5-8 11-8s11 3 11 8-7 11-11 11-11-6-11-11Z" />
          <path className="auth-avatar-smile" d="M120 101v9m0 0c-7 8-16 8-23 2m23-2c7 8 16 8 23 2" />
          <path className="auth-avatar-tongue" d="M111 113c4 5 14 5 18 0v11c-4 7-14 7-18 0Z" />
        </g>
        <g className="auth-avatar-paws">
          <path d="M91 145c-8-12-9-23-6-34m64 34c8-12 9-23 6-34" />
          <ellipse cx="84" cy="108" rx="16" ry="12" />
          <ellipse cx="156" cy="108" rx="16" ry="12" />
          <path className="auth-avatar-paw-mark" d="M80 108h8m64 0h8" />
        </g>
      </svg>
    </div>
  );
}
