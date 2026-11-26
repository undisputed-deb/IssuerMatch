import { useState } from 'react';
import { api, signIn, signOut, signUp } from '../api.js';
import { Button, ErrorBox, Input, Loading, ROLE_LABELS, useLoad, when } from '../ui.jsx';

/**
 * The page behind a team invitation link. It shows the firm and the role the inviter chose; there is nothing to pick.
 * The server checks the link, the address and the role. `onJoined` goes to the deal feed.
 */
export default function Invite({ token, user, onSignedIn, onJoined, onLeave }) {
  const preview = useLoad(() => api.invitePreview(token), [token]);
  const [mode, setMode] = useState('create');
  const [form, setForm] = useState({ fullName: '', email: '', password: '' });
  const [state, setState] = useState({ busy: false, error: null });
  const set = (name) => (e) => setForm({ ...form, [name]: e.target.value });
  const invite = preview.data;

  async function run(work) {
    setState({ busy: true, error: null });
    try {
      await work();
    } catch (error) {
      setState({ busy: false, error });
    }
  }

  const accept = async () => {
    await api.acceptInvite(token);
    onJoined();
  };

  function submit(event) {
    event.preventDefault();
    run(async () => {
      const signedIn = await (mode === 'create'
        ? signUp({ ...form, role: 'INVESTOR', inviteToken: token })
        : signIn(form.email, form.password));
      onSignedIn(signedIn); // from here the page offers "Accept", so a failure below can be retried
      await accept();
    });
  }

  const investor = user?.roles.includes('INVESTOR');
  return (
    <main className="mx-auto max-w-md px-4 py-10">
      <p className="font-mono text-lg">Issuer<span className="text-brand">Match</span></p>
      {preview.loading && <Loading label="Checking your invitation" />}
      {preview.error && (
        <>
          <h1 className="mt-6 text-2xl font-semibold">This invitation cannot be used</h1>
          <p className="mt-2">{preview.error.status === 404
            ? 'The link has expired, was revoked or has already been used. Ask your Managing Director for a new one.'
            : preview.error.message}</p>
          <div className="mt-4 flex flex-wrap gap-3">
            {preview.error.status !== 404 && <Button onClick={preview.reload}>Try again</Button>}
            <Button tone="plain" onClick={onLeave}>Go to IssuerMatch</Button>
          </div>
        </>
      )}
      {invite && (
        <>
          <h1 className="mt-6 text-2xl font-semibold">Join {invite.firmName}</h1>
          <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 border-y border-rule py-3 text-sm">
            <dt className="text-soft">Your role</dt><dd className="font-medium">{ROLE_LABELS[invite.orgRole]}</dd>
            <dt className="text-soft">Invited address</dt><dd className="font-mono break-all">{invite.maskedEmail}</dd>
            <dt className="text-soft">Link expires</dt><dd>{when(invite.expiresAt)}</dd>
          </dl>
          {!user && (
            <>
              <div role="group" aria-label="Create an account or sign in" className="mt-6 flex border-b-2 border-ink">
                {[['create', 'Create account'], ['signin', 'I have an account']].map(([key, label]) => (
                  <button key={key} type="button" aria-pressed={mode === key}
                    onClick={() => { setMode(key); setState({ busy: false, error: null }); }}
                    className={`min-h-11 flex-1 font-medium transition-colors ${mode === key ? 'bg-ink text-white' : 'text-soft hover:text-ink'}`}>
                    {label}
                  </button>
                ))}
              </div>
              <form onSubmit={submit} className="mt-4 space-y-4">
                {mode === 'create' && (
                  <Input label="Full name" required maxLength={100} autoComplete="name" value={form.fullName} onChange={set('fullName')} />
                )}
                <Input label="Email" type="email" required maxLength={254} autoComplete="username" value={form.email} onChange={set('email')}
                  hint={`Only the invited address (${invite.maskedEmail}) can join.`} />
                <Input label="Password" type="password" required maxLength={72} minLength={mode === 'create' ? 10 : undefined}
                  autoComplete={mode === 'create' ? 'new-password' : 'current-password'} value={form.password} onChange={set('password')}
                  hint={mode === 'create' ? 'At least 10 characters.' : undefined} />
                <ErrorBox error={state.error} />
                <Button type="submit" busy={state.busy}>{mode === 'create' ? 'Create account and join' : 'Sign in and join'}</Button>
              </form>
            </>
          )}
          {user && (
            <div className="mt-6 space-y-3">
              <p className="text-sm">Signed in as <b className="break-all">{user.email}</b>.</p>
              {!investor && <p className="text-sm">Only an investor account can join a firm. Sign out and use the invited address.</p>}
              <ErrorBox error={state.error} />
              <div className="flex flex-wrap gap-3">
                {investor && <Button busy={state.busy} onClick={() => run(accept)}>Accept &amp; Join Firm</Button>}
                <Button tone="plain" disabled={state.busy} onClick={() => { setState({ busy: false, error: null }); signOut(); }}>Sign out</Button>
                <Button tone="plain" disabled={state.busy} onClick={onLeave}>Not now</Button>
              </div>
            </div>
          )}
        </>
      )}
    </main>
  );
}
