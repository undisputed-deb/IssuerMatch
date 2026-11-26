// Small shared pieces. All server text is rendered as React text nodes, which escapes it. Never use dangerouslySetInnerHTML.
import { useCallback, useEffect, useState } from 'react';

/** Loads data, exposes loading/error state and a reload function. `pollEvery(data)` returns milliseconds, or 0 to stop. */
export function useLoad(load, deps, pollEvery) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const reload = useCallback(async () => {
    try {
      const data = await load();
      setState({ data, error: null, loading: false });
    } catch (error) {
      setState((s) => ({ ...s, error, loading: false }));
    }
  }, deps);
  useEffect(() => {
    setState({ data: null, error: null, loading: true });
    reload();
  }, [reload]);
  const interval = (pollEvery && state.data && pollEvery(state.data)) || 0;
  useEffect(() => {
    if (!interval) return undefined;
    // a hidden tab does not poll: it would spend the rate limit for nobody
    const timer = setInterval(() => document.hidden || reload(), interval);
    return () => clearInterval(timer);
  }, [interval, reload]);
  return { ...state, reload };
}

export function Loading({ label = 'Loading' }) {
  return (
    <div role="status" className="space-y-3 py-6" aria-live="polite">
      <span className="sr-only">{label}</span>
      {[0, 1, 2].map((i) => (
        <div key={i} className="h-12 animate-pulse rounded-sm bg-rule/50" />
      ))}
    </div>
  );
}

export function ErrorBox({ error, onRetry }) {
  if (!error) return null;
  return (
    <div role="alert" className="my-4 flex flex-wrap items-center justify-between gap-3 border-l-4 border-bad bg-bad-bg px-4 py-3 text-bad">
      <span>{error.message}</span>
      {onRetry && (
        <button type="button" onClick={onRetry} className="min-h-11 px-3 font-medium underline">
          Try again
        </button>
      )}
    </div>
  );
}

const STATUS = {
  REQUESTED: ['Queued', 'bg-rule/60 text-ink', '○'],
  RUNNING: ['Running', 'bg-rule/60 text-ink', '◐'],
  MATCHING: ['Matching', 'bg-rule/60 text-ink', '◐'],
  SUBMITTED: ['Submitted', 'bg-rule/60 text-ink', '○'],
  PENDING_REVIEW: ['Needs review', 'bg-flag-bg text-flag', '▲'],
  APPROVED: ['Approved', 'bg-good-bg text-good', '✓'],
  MATCHED: ['Matched', 'bg-good-bg text-good', '✓'],
  REJECTED: ['Rejected', 'bg-bad-bg text-bad', '✕'],
  FAILED: ['Failed', 'bg-bad-bg text-bad', '✕'],
  CLOSED: ['Closed', 'bg-rule/60 text-ink', '■'],
};

/** Status as shape plus word, so it never depends on colour alone. */
export function Status({ value }) {
  const [label, tone, mark] = STATUS[value] ?? [value, 'bg-rule/60 text-ink', '○'];
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-sm px-2 py-0.5 text-sm font-medium whitespace-nowrap ${tone}`}>
      <span aria-hidden="true">{mark}</span>
      {label}
    </span>
  );
}

export function Button({ children, tone = 'primary', busy, ...props }) {
  const tones = {
    primary: 'bg-brand text-white hover:bg-brand-dark',
    plain: 'border border-ink/30 bg-sheet text-ink hover:border-ink',
    danger: 'border border-bad bg-sheet text-bad hover:bg-bad-bg',
  };
  return (
    <button
      type="button"
      {...props}
      disabled={busy || props.disabled}
      className={`min-h-11 rounded-sm px-5 font-medium transition-colors duration-150 disabled:opacity-50 ${tones[tone]}`}
    >
      {busy ? 'Working…' : children}
    </button>
  );
}

export function PageTitle({ title, children }) {
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-3 border-b-2 border-ink pb-3">
      <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">{title}</h1>
      <div className="text-sm text-soft">{children}</div>
    </header>
  );
}

export const money = (v) => new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(v);
export const when = (iso) => (iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '—');
export const words = (v) => v.toLowerCase().replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase());
