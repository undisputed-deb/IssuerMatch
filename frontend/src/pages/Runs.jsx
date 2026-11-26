import { useState } from 'react';
import { api } from '../api.js';
import { Button, ErrorBox, Loading, PageTitle, Status, useLoad, when } from '../ui.jsx';

export default function Runs() {
  const [page, setPage] = useState(0);
  const runs = useLoad(
    async () => {
      const [list, needs] = await Promise.all([api.runs(page), api.needs()]);
      const titles = new Map(needs.items.map((n) => [n.id, n.title]));
      return { ...list, items: list.items.map((r) => ({ ...r, title: titles.get(r.needId) ?? `Need ${r.needId.slice(0, 8)}` })) };
    },
    [page],
    // quick while a run is active; the newest page also refreshes slowly, because a just-submitted run appears a moment later
    (d) => (d.items.some((r) => r.status === 'REQUESTED' || r.status === 'RUNNING') ? 3000 : page === 0 ? 5000 : 0),
  );
  const pages = runs.data ? Math.max(1, Math.ceil(runs.data.total / runs.data.size)) : 1;

  return (
    <>
      <PageTitle title="Match runs">{runs.data && `${runs.data.total} runs, newest first`}</PageTitle>
      <ErrorBox error={runs.error} onRetry={runs.reload} />
      {runs.loading && <Loading />}
      {runs.data?.items.length === 0 && <p>No runs yet. Submit a funding need to start one.</p>}
      {runs.data?.items.length > 0 && (
        <div className="overflow-x-auto border-y border-rule bg-sheet">
          <table className="w-full min-w-[640px] text-left">
            <thead className="border-b border-ink text-sm text-soft">
              <tr>
                <th className="px-4 py-2 font-medium">Funding need</th>
                <th className="px-4 py-2 font-medium">Status</th>
                <th className="px-4 py-2 text-right font-medium">Tokens</th>
                <th className="px-4 py-2 text-right font-medium">Time</th>
                <th className="px-4 py-2 font-medium">Started</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-rule">
              {runs.data.items.map((r) => (
                <tr key={r.id} className="transition-colors hover:bg-paper">
                  <td className="px-4 py-3">
                    <a className="font-medium text-brand underline-offset-4 hover:underline" href={`#/runs/${r.id}`}>{r.title}</a>
                    {r.inputFlags.length > 0 && (
                      <span className="ml-2 rounded-sm bg-flag-bg px-1.5 text-xs font-medium text-flag">▲ suspicious text</span>
                    )}
                  </td>
                  <td className="px-4 py-3"><Status value={r.status} /></td>
                  <td className="px-4 py-3 text-right font-mono text-sm">{r.tokensIn == null ? '—' : r.tokensIn + r.tokensOut}</td>
                  <td className="px-4 py-3 text-right font-mono text-sm">{r.latencyMs == null ? '—' : `${(r.latencyMs / 1000).toFixed(1)}s`}</td>
                  <td className="px-4 py-3 text-sm text-soft">{when(r.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {pages > 1 && (
        <nav aria-label="Pages" className="mt-4 flex items-center gap-4">
          <Button tone="plain" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</Button>
          <span className="text-sm text-soft">Page {page + 1} of {pages}</span>
          <Button tone="plain" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Older</Button>
        </nav>
      )}
    </>
  );
}
