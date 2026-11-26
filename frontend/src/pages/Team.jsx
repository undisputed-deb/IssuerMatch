import { useState } from 'react';
import { api } from '../api.js';
import { Button, ErrorBox, Field, Input, Loading, PageTitle, ROLE_LABELS, control, useLoad, when } from '../ui.jsx';

const ROLES = Object.keys(ROLE_LABELS);
const ROLE_HELP = {
  VIEWER: 'Reads deals, questions and offers.',
  ANALYST: 'Also asks questions and passes on deals.',
  MANAGING_DIRECTOR: 'Also submits offers, edits the profile and manages the team.',
};

/** Everyone in the firm sees the roster. Only a Managing Director sees invitations and controls; the server enforces it. */
export default function Team({ onMembershipChanged }) {
  const team = useLoad(api.team, []);
  const [state, setState] = useState({ busy: false, error: null });
  const director = team.data?.myOrgRole === 'MANAGING_DIRECTOR';

  async function change(work, mine) {
    setState({ busy: true, error: null });
    try {
      await work();
      setState({ busy: false, error: null });
    } catch (error) {
      setState({ busy: false, error });
    }
    if (mine) await onMembershipChanged(); // my own role or membership may be different now
    await team.reload();
  }

  return (
    <>
      <PageTitle title="Team">{team.data && `${team.data.members.length} in your firm`}</PageTitle>
      <ErrorBox error={team.error} onRetry={team.reload} />
      <ErrorBox error={state.error} />
      {team.loading && <Loading />}
      <ul className="divide-y divide-rule border-y border-rule bg-sheet empty:hidden">
        {team.data?.members.map((m) => (
          <Member key={m.userId} member={m} director={director} busy={state.busy}
            onRole={(role) => change(() => api.changeRole(m.userId, role), m.you)}
            onRemove={() => change(() => api.removeMember(m.userId), m.you)} />
        ))}
      </ul>
      {director && (
        <>
          <InviteForm onInvited={team.reload} />
          <section aria-label="Pending invitations" className="mt-10">
            <h2 className="text-lg font-semibold">Pending invitations</h2>
            {team.data.pendingInvites.length === 0 && <p className="text-sm text-soft">None.</p>}
            <ul className="mt-2 divide-y divide-rule border-y border-rule bg-sheet empty:hidden">
              {team.data.pendingInvites.map((i) => (
                <li key={i.id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-2">
                  <div className="min-w-0">
                    <p className="font-medium break-all">{i.email}</p>
                    <p className="text-sm text-soft">{ROLE_LABELS[i.orgRole]} · expires {when(i.expiresAt)}</p>
                  </div>
                  <Button tone="danger" disabled={state.busy} aria-label={`Revoke invitation for ${i.email}`}
                    onClick={() => change(() => api.revokeInvite(i.id))}>Revoke</Button>
                </li>
              ))}
            </ul>
          </section>
        </>
      )}
      {team.data && !director && <p className="mt-4 text-sm text-soft">A Managing Director invites people and changes roles.</p>}
    </>
  );
}

function Member({ member: m, director, busy, onRole, onRemove }) {
  const [removing, setRemoving] = useState(false);
  const name = m.displayName || m.email || 'Team member';
  return (
    <li className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
      <div className="min-w-0">
        <p className="font-medium break-words">{name}{m.you && <span className="ml-2 text-sm font-normal text-soft">(you)</span>}</p>
        <p className="text-sm text-soft break-all">{m.email ?? 'No email on file'} · joined {when(m.joinedAt)}</p>
      </div>
      {!director && <p className="text-sm font-medium">{ROLE_LABELS[m.orgRole]}</p>}
      {director && (removing ? (
        <div role="group" aria-label={`Confirm removing ${name}`} className="flex flex-wrap items-center gap-3">
          <p className="text-sm font-medium">Remove {m.you ? 'yourself' : name} from the firm?</p>
          <Button tone="danger" busy={busy} onClick={() => { setRemoving(false); onRemove(); }}>Yes, remove</Button>
          <Button tone="plain" onClick={() => setRemoving(false)}>Cancel</Button>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-3">
          <select className="min-h-11 rounded-sm border border-ink/30 bg-sheet px-3" aria-label={`Role of ${name}`} value={m.orgRole} disabled={busy}
            onChange={(e) => onRole(e.target.value)}>
            {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
          </select>
          <Button tone="plain" disabled={busy} aria-label={`Remove ${name}`} onClick={() => setRemoving(true)}>Remove</Button>
        </div>
      ))}
    </li>
  );
}

/** The link appears once, here: the server keeps only a digest of it and cannot show it again. */
function InviteForm({ onInvited }) {
  const [form, setForm] = useState({ email: '', orgRole: 'ANALYST' });
  const [state, setState] = useState({ busy: false, error: null });
  const [link, setLink] = useState(null);
  const [copied, setCopied] = useState(false);

  async function send(event) {
    event.preventDefault();
    setState({ busy: true, error: null });
    try {
      const made = await api.invite(form.email, form.orgRole);
      setLink({ url: `${window.location.origin}${made.invitePath}`, email: made.email, expiresAt: made.expiresAt });
      setCopied(false);
      setForm({ ...form, email: '' });
      setState({ busy: false, error: null });
      await onInvited();
    } catch (error) {
      setState({ busy: false, error });
    }
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(link.url);
      setCopied(true);
    } catch {
      setState({ busy: false, error: new Error('Could not copy. Select the link and copy it yourself.') });
    }
  }

  return (
    <section aria-label="Invite teammate" className="mt-10 max-w-3xl">
      <h2 className="text-lg font-semibold">Invite teammate</h2>
      <p className="text-sm text-soft">No email is sent. Copy the link and give it to the person yourself.</p>
      <form onSubmit={send} className="mt-3 grid items-end gap-4 sm:grid-cols-2">
        <Input label="Their email" type="email" required maxLength={254} value={form.email}
          onChange={(e) => setForm({ ...form, email: e.target.value })} />
        <Field label="Role">
          <select className={control} value={form.orgRole} onChange={(e) => setForm({ ...form, orgRole: e.target.value })}>
            {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
          </select>
        </Field>
        <p className="text-sm text-soft sm:col-span-2">{ROLE_HELP[form.orgRole]}</p>
        <div className="sm:col-span-2">
          <ErrorBox error={state.error} />
          <Button type="submit" busy={state.busy}>Create invite link</Button>
        </div>
      </form>
      {link && (
        <div role="status" className="mt-4 space-y-2 border-l-4 border-good bg-good-bg p-4">
          <p className="text-sm font-medium break-words">Link for {link.email}, valid until {when(link.expiresAt)}. It is shown only now.</p>
          <div className="flex flex-wrap gap-3">
            <input readOnly aria-label="Invite link" className={`${control} mt-0 min-w-0 flex-1 basis-64 font-mono text-sm`} value={link.url}
              onFocus={(e) => e.target.select()} />
            <Button onClick={copy}>{copied ? 'Copied' : 'Copy Invite Link'}</Button>
          </div>
        </div>
      )}
    </section>
  );
}
