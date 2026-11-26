import { useState } from 'react';
import { api } from '../api.js';
import { Button, ErrorBox, Loading, PageTitle, useLoad, when } from '../ui.jsx';

export default function Reconciliation() {
  const latest = useLoad(async () => {
    try {
      return await api.reconciliationLatest();
    } catch (e) {
      if (e.status === 404) return { never: true };
      throw e;
    }
  }, []);
  const [state, setState] = useState({ busy: false, error: null });

  async function run() {
    setState({ busy: true, error: null });
    try {
      await api.reconciliationRun();
      setState({ busy: false, error: null });
    } catch (error) {
      setState({ busy: false, error });
    }
    await latest.reload();
  }

  const report = latest.data;
  return (
    <>
      <PageTitle title="Reconciliation">Compares commitments, remaining capacity and the event ledger.</PageTitle>
      <Button busy={state.busy} onClick={run}>Run now</Button>
      <ErrorBox error={state.error} />
      <ErrorBox error={latest.error} onRetry={latest.reload} />
      {latest.loading && <Loading />}
      {report?.never && <p className="mt-4">It has not run yet.</p>}
      {report && !report.never && (
        <div className="mt-4">
          <p className="font-medium">
            {report.mismatchCount === 0 ? '✓ Everything agrees' : `▲ ${report.mismatchCount} mismatch(es)`}
            <span className="ml-2 text-sm text-soft">checked {when(report.finishedAt)}</span>
          </p>
          <ul className="mt-3 divide-y divide-rule border-y border-rule bg-sheet">
            {report.mismatches.map((m, i) => (
              <li key={i} className="grid gap-1 px-4 py-2 sm:grid-cols-[14rem_1fr]">
                <span className="font-mono text-sm text-flag">{m.type}</span>
                <span className="text-sm">{m.detail} <span className="font-mono text-soft">{(m.commitmentId || m.needId || '').slice(0, 8)}</span></span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </>
  );
}
