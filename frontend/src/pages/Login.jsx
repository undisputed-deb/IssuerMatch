import { useState } from 'react';
import { signIn } from '../api.js';
import { Button, ErrorBox } from '../ui.jsx';

export default function Login({ onSignedIn }) {
  const [form, setForm] = useState({ email: '', password: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSignedIn(await signIn(form.email, form.password));
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  const field = (name, label, type, autoComplete) => (
    <label className="block">
      <span className="text-sm font-medium">{label}</span>
      <input required name={name} type={type} autoComplete={autoComplete} value={form[name]}
        onChange={(e) => setForm({ ...form, [name]: e.target.value })}
        className="mt-1 min-h-11 w-full rounded-sm border border-ink/30 bg-sheet px-3" />
    </label>
  );

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
          <li><b className="font-mono text-amber-300">3</b> A person approves or rejects. Nothing is sent without them.</li>
        </ol>
      </section>
      <section className="flex items-center p-8 lg:p-16">
        <form onSubmit={submit} className="w-full max-w-sm space-y-4">
          <h2 className="text-xl font-semibold">Sign in</h2>
          {field('email', 'Email', 'email', 'username')}
          {field('password', 'Password', 'password', 'current-password')}
          <ErrorBox error={error} />
          <Button type="submit" busy={busy}>Sign in</Button>
        </form>
      </section>
    </main>
  );
}
