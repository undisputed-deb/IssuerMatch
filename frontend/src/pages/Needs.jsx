import { useState } from 'react';
import { api } from '../api.js';
import { Offers, Thread } from '../deal.jsx';
import { Button, ErrorBox, Loading, PageTitle, Status, money, useLoad, when, words } from '../ui.jsx';

/** `deals` = 'issuer' (answer questions) or 'staff' (read only). */
export default function Needs({ title = 'Funding needs', canRematch = false, empty = 'No funding needs yet.', deals = 'staff' }) {
  const needs = useLoad(api.needs, [], (d) => (d.items.some((n) => n.status === 'MATCHING' || n.status === 'SUBMITTED') ? 5000 : 0));
  const [actionError, setActionError] = useState(null);

  async function rematch(id) {
    setActionError(null);
    try {
      await api.rematch(id);
      await needs.reload();
    } catch (e) {
      setActionError(e);
    }
  }

  return (
    <>
      <PageTitle title={title}>{needs.data && `${needs.data.total} in total`}</PageTitle>
      <ErrorBox error={needs.error} onRetry={needs.reload} />
      <ErrorBox error={actionError} />
      {needs.loading && <Loading />}
      {needs.data?.items.length === 0 && <p>{empty}</p>}
      <ul className="divide-y divide-rule border-y border-rule bg-sheet">
        {needs.data?.items.map((n) => (
          <li key={n.id} className="grid items-center gap-2 px-4 py-3 sm:grid-cols-[1fr_auto_auto_auto] sm:gap-6">
            <div>
              <p className="font-medium">{n.title}</p>
              <p className="text-sm text-soft">{words(n.sector)} · {words(n.region)} · {words(n.instrument)} · {when(n.createdAt)}</p>
            </div>
            <p className="font-mono sm:text-right">{money(n.amount)}</p>
            <Status value={n.status} />
            {canRematch && n.status === 'SUBMITTED'
              ? <Button tone="plain" onClick={() => rematch(n.id)}>Retry matching</Button>
              : <span aria-hidden="true" />}
            <Deal needId={n.id} mode={deals} />
          </li>
        ))}
      </ul>
    </>
  );
}

/** Loaded only once opened, so a long list does not fire two requests per row. */
function Deal({ needId, mode }) {
  const [opened, setOpened] = useState(false);
  return (
    <details className="sm:col-span-4" onToggle={(e) => e.currentTarget.open && setOpened(true)}>
      <summary className="flex min-h-11 items-center font-medium text-brand">Offers and questions</summary>
      {opened && (
        <div className="space-y-6 pb-3">
          <Offers needId={needId} />
          <Thread needId={needId} mode={mode} />
        </div>
      )}
    </details>
  );
}
