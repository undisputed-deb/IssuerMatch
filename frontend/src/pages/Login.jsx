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
  const [rolePicked, setRolePicked] = useState(false);
  const creating = mode === 'signup';
  const set = (name) => (e) => setForm({ ...form, [name]: e.target.value });
  const emailLength = Math.min(form.email.length, 40);
  const pupilX = Math.round(Math.min(4, emailLength * 0.1));
  const headTilt = creating ? (form.role === 'INVESTOR' ? -4 : 3) : Math.max(-7, Math.min(1, 1 - Math.max(form.email.length - 10, 0) * 0.22));

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

  const speech = error
    ? "Nope. Nice try—I'm not bypassing JWTs."
    : focusedField === 'password'
      ? (showPassword ? "Bold move. Hope nobody's behind you." : "Paws up. I'm not looking, promise.")
      : focusedField === 'email'
        ? "Checking if you're actually on the cap table..."
        : creating
          ? !rolePicked
            ? 'New here? Pick a side—pitch deck or checkbook?'
            : form.role === 'INVESTOR'
              ? 'Big check energy. Better have a real thesis.'
              : "Raising capital? Bold. Let's see the numbers."
          : "So... raising money, or pretending to deploy it?";
  const avatarState = [
    focusedField === 'password' ? (showPassword ? 'password-visible' : 'password-hidden') : focusedField,
    error ? 'error' : '',
    creating ? `signup-${form.role.toLowerCase()}` : 'signin',
  ].filter(Boolean).join(' ');

  return (
    <main className="auth-layout grid min-h-screen lg:grid-cols-[55fr_45fr]">
      <section className="auth-brand flex flex-col justify-between bg-ink px-6 py-5 text-white sm:px-8 lg:px-14 lg:py-10">
        <header className="auth-brand-top flex flex-wrap items-center justify-between gap-3">
          <p className="font-mono text-xl font-semibold tracking-tight">Issuer<span className="text-amber-300">Match</span></p>
          <span className="rounded-full border border-white/20 bg-white/5 px-3 py-1 font-mono text-[0.62rem] tracking-[0.12em] text-white/75">
            CAPITAL MATCHING · SYNTHETIC DATA
          </span>
        </header>

        <div className="auth-brand-middle mx-auto w-full max-w-2xl py-10 lg:py-12">
          <h1 className="max-w-2xl text-3xl leading-tight font-semibold tracking-tight sm:text-4xl xl:text-5xl">
            Ranked investors, with the evidence behind every rank.
          </h1>
          <div className="mt-8 max-w-md rounded-xl border border-white/10 bg-white/[0.04] p-5 shadow-lg shadow-black/10 backdrop-blur-sm">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="font-mono text-xs text-white/70">Sample match · Seed · $2.5M USD</p>
              <span className="rounded-full border border-emerald-300/30 bg-emerald-300/10 px-2.5 py-1 text-[0.68rem] font-medium text-emerald-200">Human-approved</span>
            </div>
            <div className="mt-5 flex items-baseline justify-between gap-3">
              <h2 className="font-semibold tracking-tight">Northbridge Capital</h2>
              <strong className="font-mono text-lg text-amber-300">86.3 <span className="text-sm text-white/50">/ 100</span></strong>
            </div>
            <div className="mt-4 space-y-3">
              <ScoreBar label="Mandate" score="25/25" percent="100%" />
              <ScoreBar label="Ticket" score="20/20" percent="100%" />
              <ScoreBar label="Thesis" score="9/10" percent="90%" />
            </div>
            <p className="mt-5 border-t border-white/10 pt-3 font-mono text-[0.62rem] tracking-wide text-white/55">
              Read-only agent · Deterministic score · Full audit trail
            </p>
          </div>
        </div>

        <footer className="auth-brand-steps border-t border-white/20 pt-5">
          <ol className="grid gap-3 text-sm leading-relaxed text-white/75 lg:grid-cols-3 lg:gap-5">
            <Step number="01">An issuer submits a structured funding need.</Step>
            <Step number="02">A read-only agent gathers thesis evidence. Deterministic rules compute each score.</Step>
            <Step number="03">Review staff approve before matched investor firms see the deal.</Step>
          </ol>
        </footer>
      </section>

      <section className="auth-panel flex min-w-0 items-center justify-center bg-paper px-4 py-7 sm:px-8 lg:px-10 lg:py-12">
        <div className="w-full max-w-sm">
          <div className="auth-card overflow-hidden rounded-xl border border-slate-200/80 bg-white shadow-sm">
            <div className="auth-mascot-stage">
              <p className="auth-speech" aria-live="polite" aria-atomic="true">{speech}</p>
              <AuthAvatar state={avatarState} headTilt={headTilt} pupilX={pupilX} />
            </div>
            <div className="px-5 pb-6 sm:px-7 sm:pb-7">
              <div role="group" aria-label="Sign in or create an account" className="mb-5 flex border-b-2 border-ink">
                { [['signin', 'Sign in'], ['signup', 'Create account']].map(([key, label]) => (
                  <button key={key} type="button" aria-pressed={mode === key} onClick={() => switchTo(key)}
                    className={`min-h-11 flex-1 font-medium transition-colors ${mode === key ? 'bg-ink text-white' : 'text-soft hover:text-ink'}`}>
                    {label}
                  </button>
                )) }
              </div>
              <form onSubmit={submit} className="space-y-4">
                <h2 className="sr-only">{creating ? 'Create account' : 'Sign in'}</h2>
                {creating && (
                  <Field label="Full name">
                    <input className={control} required maxLength={100} autoComplete="name" value={form.fullName} onChange={set('fullName')} />
                  </Field>
                )}
                <Field label="Email">
                  <input className={control} required type="email" maxLength={254} autoComplete="email" value={form.email} onChange={set('email')}
                    onFocus={() => setFocusedField('email')} onBlur={() => setFocusedField(null)} />
                </Field>
                <div className="space-y-1">
                  <label htmlFor="auth-password" className="block text-sm font-medium">Password</label>
                  <div className="relative" onFocus={() => setFocusedField('password')}
                    onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) setFocusedField(null); }}>
                    <input id="auth-password" className={`${control} pr-20`} required type={showPassword ? 'text' : 'password'} minLength={creating ? 10 : undefined} maxLength={72}
                      autoComplete={creating ? 'new-password' : 'current-password'} value={form.password} onChange={set('password')} />
                    <button type="button" aria-pressed={showPassword} aria-label={showPassword ? 'Mask characters' : 'Show characters'}
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
                          <input type="radio" name="role" value={value} checked={form.role === value}
                            onChange={(event) => { set('role')(event); setRolePicked(true); }} className="mr-2" />
                          <span className="font-medium">{label}</span>
                          <span className="mt-1 block text-xs text-soft">{hint}</span>
                        </label>
                      ))}
                    </div>
                  </fieldset>
                )}
                <ErrorBox error={error} />
                <div className="[&>button]:w-full">
                  <Button type="submit" busy={busy}>{creating ? 'Create my account' : 'Sign in'}</Button>
                </div>
              </form>
            </div>
          </div>
          <p className="mt-4 text-center text-xs text-soft">Synthetic data · Not affiliated with any company.</p>
        </div>
      </section>
    </main>
  );
}

function ScoreBar({ label, score, percent }) {
  return (
    <div className="grid grid-cols-[4.25rem_1fr_2.6rem] items-center gap-2 text-xs">
      <span className="text-white/65">{label}</span>
      <span className="h-1.5 overflow-hidden rounded-full bg-white/10">
        <span className="block h-full rounded-full bg-amber-300" style={{ width: percent }} />
      </span>
      <span className="text-right font-mono text-white/75">{score}</span>
    </div>
  );
}

function Step({ number, children }) {
  return <li className="flex gap-2"><span className="font-mono text-amber-300">{number}</span><span>{children}</span></li>;
}

function AuthAvatar({ state, headTilt, pupilX }) {
  return (
    <div className={`auth-avatar ${state}`} aria-hidden="true" style={{ '--head-tilt': `${headTilt}deg`, '--pupil-x': `${pupilX}px` }}>
      <svg viewBox="0 0 240 176" role="presentation" focusable="false">
        <ellipse className="auth-avatar-shadow" cx="120" cy="164" rx="70" ry="8" />
        <g className="auth-avatar-head">
          <path className="auth-avatar-ear auth-avatar-ear-left" d="M77 48C56 17 42 16 38 21c-3 4 3 35 22 51Z" />
          <path className="auth-avatar-ear auth-avatar-ear-right" d="M163 48c21-31 35-32 39-27 3 4-3 35-22 51Z" />
          <ellipse className="auth-avatar-fur" cx="120" cy="78" rx="69" ry="57" />
          <ellipse className="auth-avatar-muzzle" cx="120" cy="99" rx="38" ry="24" />
          <g className="auth-avatar-eyes">
            <ellipse className="auth-avatar-eye" cx="96" cy="71" rx="8" ry="10" />
            <ellipse className="auth-avatar-eye" cx="144" cy="71" rx="8" ry="10" />
            <g className="auth-avatar-pupils"><circle cx="96" cy="71" r="3.5" /><circle cx="144" cy="71" r="3.5" /></g>
          </g>
          <path className="auth-avatar-nose" d="M109 91c0-5 5-8 11-8s11 3 11 8-7 11-11 11-11-6-11-11Z" />
          <path className="auth-avatar-smile" d="M120 101v9m0 0c-7 8-16 8-23 2m23-2c7 8 16 8 23 2" />
          <path className="auth-avatar-tongue" d="M111 113c4 5 14 5 18 0v11c-4 7-14 7-18 0Z" />
        </g>
        <g className="auth-avatar-paws">
          <path d="M91 145c-8-12-9-23-6-34" />
          <path className="auth-avatar-paw-right" d="M149 145c8-12 9-23 6-34" />
          <ellipse cx="84" cy="108" rx="16" ry="12" />
          <ellipse className="auth-avatar-paw-right" cx="156" cy="108" rx="16" ry="12" />
          <path className="auth-avatar-paw-mark" d="M80 108h8m64 0h8" />
        </g>
      </svg>
    </div>
  );
}
