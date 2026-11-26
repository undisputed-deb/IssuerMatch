import { useState } from 'react';
import { api } from '../api.js';
import { Button, ErrorBox, Loading, PageTitle, useLoad, when } from '../ui.jsx';

export default function DeadLetters() {
  const letters = useLoad(api.deadLetters, []);
  const [error, setError] = useState(null);

  async function replay(id) {
    setError(null);
    try {
      await api.replayDeadLetter(id);
    } catch (e) {
      setError(e);
    }
    await letters.reload();
  }

  return (
    <>
      <PageTitle title="Dead letters">Messages a consumer gave up on. Replay after fixing the cause.</PageTitle>
      <ErrorBox error={letters.error} onRetry={letters.reload} />
      <ErrorBox error={error} />
      {letters.loading && <Loading />}
      {letters.data?.items.length === 0 && <p>No failed messages.</p>}
      <ul className="space-y-3">
        {letters.data?.items.map((l) => (
          <li key={l.id} className="border border-rule bg-sheet p-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="font-mono">{l.sourceTopic} <span className="text-sm text-soft">{when(l.receivedAt)}</span></p>
              <Button tone="plain" onClick={() => replay(l.id)}>Replay</Button>
            </div>
            <p className="mt-1 text-sm text-bad">{l.reason}</p>
            <details className="mt-1">
              <summary className="flex min-h-11 items-center text-sm text-brand">Original message</summary>
              <pre className="max-h-48 overflow-auto bg-paper p-2 font-mono text-xs whitespace-pre-wrap">{l.payload}</pre>
            </details>
          </li>
        ))}
      </ul>
    </>
  );
}
