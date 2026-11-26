import { useState } from 'react';
import { api } from '../api.js';
import { Button, ErrorBox, Loading, PageTitle, money, useLoad, when } from '../ui.jsx';

/** Flagged commitments waiting for a second person. */
export default function RiskReview() {
  const queue = useLoad(async () => {
    const page = await api.commitments('PENDING_REVIEW');
    const details = await Promise.all(page.items.map((c) => api.commitment(c.id)));
    return page.items.map((c, i) => ({ ...c, decisions: details[i].decisions }));
  }, []);
  const [error, setError] = useState(null);

  async function decide(id, approve) {
    setError(null);
    try {
      await api.reviewCommitment(id, approve);
    } catch (e) {
      setError(e);
    }
    await queue.reload();
  }

  return (
    <>
      <PageTitle title="Risk review">A flag needs a second person: you cannot review what you recorded.</PageTitle>
      <ErrorBox error={queue.error} onRetry={queue.reload} />
      <ErrorBox error={error} />
      {queue.loading && <Loading />}
      {queue.data?.length === 0 && <p>Nothing is waiting for review.</p>}
      <ul className="space-y-3">
        {queue.data?.map((c) => (
          <li key={c.id} className="border border-rule bg-sheet p-4">
            <div className="flex flex-wrap justify-between gap-2">
              <p className="font-medium">{c.investorName} → {c.needTitle}</p>
              <p className="font-mono text-lg">{money(c.amount)}</p>
            </div>
            <p className="text-sm text-soft">{when(c.createdAt)}</p>
            <ul className="mt-2 space-y-1 text-sm">
              {c.decisions.filter((d) => d.verdict !== 'PASS').map((d) => (
                <li key={d.rule}><b className="font-mono text-flag">{d.verdict} {d.rule}</b> {d.reason}</li>
              ))}
            </ul>
            <div className="mt-3 flex gap-3">
              <Button onClick={() => decide(c.id, true)}>Approve</Button>
              <Button tone="danger" onClick={() => decide(c.id, false)}>Reject</Button>
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}
