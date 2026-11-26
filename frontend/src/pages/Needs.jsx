import { useState } from 'react';
import { api } from '../api.js';
import { Button, ErrorBox, Loading, PageTitle, Status, money, useLoad, when, words } from '../ui.jsx';

export default function Needs({ title = 'Funding needs', canRematch = false, empty = 'No funding needs yet.' }) {
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
          </li>
        ))}
      </ul>
    </>
  );
}
