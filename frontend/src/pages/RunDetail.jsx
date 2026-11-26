import { useState } from 'react';
import { api } from '../api.js';
import { Offers, Thread } from '../deal.jsx';
import { Breakdown } from '../score.jsx';
import { Button, ErrorBox, Loading, PageTitle, Status, money, useLoad, when, words } from '../ui.jsx';

const pretty = (text) => {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
};

export default function RunDetail({ id }) {
  const detail = useLoad(
    async () => {
      const [d, results] = await Promise.all([api.run(id), api.results(id)]);
      return { ...d, results, need: await api.need(d.run.needId) };
    },
    [id],
    (d) => (d.run.status === 'REQUESTED' || d.run.status === 'RUNNING' ? 3000 : 0),
  );
  const [review, setReview] = useState({ busy: null, confirm: null, error: null });

  async function decide(approve) {
    setReview({ busy: approve, confirm: null, error: null });
    try {
      await api.review(id, approve);
      setReview({ busy: null, confirm: null, error: null });
    } catch (error) {
      setReview({ busy: null, confirm: null, error });
    }
    await detail.reload();
  }

  if (detail.loading) return <Loading label="Loading run" />;
  if (!detail.data) return <ErrorBox error={detail.error} onRetry={detail.reload} />;
  const { run, results, need, toolCalls } = detail.data;

  return (
    <>
      <p className="mb-2"><a className="text-brand underline" href="#/runs">← All runs</a></p>
      <PageTitle title={need.title}><Status value={run.status} /></PageTitle>
      <ErrorBox error={detail.error} onRetry={detail.reload} />

      {run.inputFlags.length > 0 && (
        <div role="alert" className="mb-6 border-l-4 border-flag bg-flag-bg px-4 py-3 text-flag">
          <p className="font-semibold">▲ The funding text contains instruction-like content. Read this run with care.</p>
          <p className="text-sm">Matched checks: {run.inputFlags.join(', ')}</p>
        </div>
      )}
      {run.executionMode === 'DEGRADED_DETERMINISTIC' && (
        <p role="status" className="mb-6 border-l-4 border-flag bg-flag-bg px-4 py-3 text-flag">
          ▲ Matched without the language model: {run.degradedReason}. Scores are the same Java scores; the text is generated from templates.
        </p>
      )}
      {run.status === 'FAILED' && <p role="alert" className="mb-6 border-l-4 border-bad bg-bad-bg px-4 py-3 text-bad">Run failed: {run.error}</p>}

      <div className="grid gap-8 lg:grid-cols-[1fr_20rem]">
        <section aria-labelledby="ranking">
          <h2 id="ranking" className="mb-3 text-lg font-semibold">Ranked investors</h2>
          {results.length === 0 && <p className="text-soft">{run.status === 'RUNNING' || run.status === 'REQUESTED' ? 'The agent is working…' : 'No results.'}</p>}
          <ol className="space-y-3">
            {results.map((r) => (
              <li key={r.investorId} className="grid grid-cols-[3.5rem_minmax(0,1fr)] border border-rule bg-sheet">
                <div className="flex items-start justify-center border-r border-rule py-4 font-mono text-2xl">{r.rank}</div>
                <div className="p-4">
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <p className="text-lg font-medium">{r.investorName}</p>
                    <p className="font-mono text-lg"><span className="sr-only">Score </span>{Number(r.score).toFixed(1)}<span className="text-sm text-soft"> / 100</span></p>
                  </div>
                  <div aria-hidden="true" className="mt-1 h-1.5 bg-rule/60">
                    <div className="h-full origin-left bg-brand" style={{ transform: `scaleX(${r.score / 100})` }} />
                  </div>
                  <details className="mt-2">
                    <summary className="flex min-h-11 items-center font-medium text-brand">Score breakdown and investor brief</summary>
                    <div className="space-y-3 pb-2">
                      <Breakdown breakdown={r.scoreBreakdown} />
                      {r.dealBrief && <p className="max-w-prose"><b>Brief the investor will see:</b> {r.dealBrief}</p>}
                      <ul className="list-disc pl-5 text-sm">
                        {r.suggestedDiligenceQuestions.map((q) => <li key={q}>{q}</li>)}
                      </ul>
                    </div>
                  </details>
                  <details>
                    <summary className="flex min-h-11 items-center font-medium text-brand">Why this rank</summary>
                    <p className="max-w-prose whitespace-pre-wrap">{r.explanation}</p>
                    <p className="mt-2 flex flex-wrap gap-1.5 text-sm">
                      <span className="text-soft">Evidence:</span>
                      {r.citedTools.map((t) => <code key={t} className="break-all rounded-sm bg-paper px-1.5 font-mono">{t}</code>)}
                    </p>
                  </details>
                </div>
              </li>
            ))}
          </ol>

          {run.status === 'PENDING_REVIEW' && (
            <div className="mt-6 border-t-2 border-ink pt-4">
              <ErrorBox error={review.error} />
              {review.confirm === null ? (
                <div className="flex flex-wrap gap-3">
                  <Button busy={review.busy === true} onClick={() => setReview({ ...review, confirm: true })}>Approve ranking</Button>
                  <Button tone="danger" busy={review.busy === false} onClick={() => setReview({ ...review, confirm: false })}>Reject ranking</Button>
                </div>
              ) : (
                <div role="group" aria-label="Confirm decision" className="flex flex-wrap items-center gap-3">
                  <p className="font-medium">{review.confirm ? 'Approve' : 'Reject'} this ranking? This is final.</p>
                  <Button tone={review.confirm ? 'primary' : 'danger'} onClick={() => decide(review.confirm)}>Yes, {review.confirm ? 'approve' : 'reject'}</Button>
                  <Button tone="plain" onClick={() => setReview({ ...review, confirm: null })}>Cancel</Button>
                </div>
              )}
            </div>
          )}

          {run.status === 'APPROVED' && (
            <div className="mt-10 space-y-6">
              <Offers needId={run.needId} />
              <Thread needId={run.needId} mode="staff" />
            </div>
          )}

          <h2 className="mt-10 mb-3 text-lg font-semibold">Tool calls ({toolCalls.length})</h2>
          <ul className="divide-y divide-rule border-y border-rule bg-sheet">
            {toolCalls.map((c, i) => (
              <li key={i}>
                <details>
                  <summary className="flex min-h-11 flex-wrap items-center gap-3 px-4 font-mono text-sm">
                    <span className="text-soft">{String(i + 1).padStart(2, '0')}</span>
                    <span>{c.tool}</span>
                    {c.error && <span className="text-bad">✕ refused or failed</span>}
                    <span className="ml-auto text-soft">{c.ms} ms</span>
                  </summary>
                  <div className="grid gap-2 px-4 pb-4">
                    <p className="text-sm font-medium">Input</p>
                    <pre className="max-h-40 overflow-auto bg-paper p-2 font-mono text-xs">{pretty(c.input)}</pre>
                    <p className="text-sm font-medium">Result</p>
                    <pre className="max-h-64 overflow-auto bg-paper p-2 font-mono text-xs">{pretty(c.output)}</pre>
                  </div>
                </details>
              </li>
            ))}
          </ul>
        </section>

        <aside aria-label="Funding need and run facts" className="h-fit space-y-4 border-t-4 border-ink bg-sheet p-4 text-sm">
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
            {[['Amount', `${money(need.amount)} ${need.currency}`], ['Still open', money(need.remainingCapacity)], ['Stage', need.companyStage ? words(need.companyStage) : '—'], ['Sector', words(need.sector)],
              ['Region', words(need.region)], ['Instrument', words(need.instrument)], ['Risk', words(need.riskLevel)],
              ['Tokens', run.tokensIn == null ? '—' : `${run.tokensIn} in, ${run.tokensOut} out`],
              ['Duration', run.latencyMs == null ? '—' : `${(run.latencyMs / 1000).toFixed(1)} s`],
              ['Started', when(run.createdAt)], ['Reviewed', when(run.reviewedAt)]].map(([k, v]) => (
              <div key={k} className="contents"><dt className="text-soft">{k}</dt><dd className="text-right font-mono">{v}</dd></div>
            ))}
          </dl>
          <div>
            <p className="font-medium">Issuer’s description <span className="text-soft">(unverified text)</span></p>
            <p className="mt-1 max-h-64 overflow-auto whitespace-pre-wrap break-words">{need.description}</p>
          </div>
        </aside>
      </div>
    </>
  );
}
