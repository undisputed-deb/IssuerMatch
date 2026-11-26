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
  ACCEPTED: ['Accepted', 'bg-good-bg text-good', '✓'],
  OFFER_AWAITING: ['Awaiting company answer', 'bg-flag-bg text-flag', '▲'],
  OFFER_ACCEPTED: ['Accepted by company', 'bg-good-bg text-good', '✓'],
  OFFER_DECLINED: ['Declined by company', 'bg-bad-bg text-bad', '✕'],
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

/** Where an offer stands: risk rules and review first, then the company's own answer. */
export function offerStatus(offer) {
  if (offer.status !== 'ACCEPTED') return offer.status;
  return offer.issuerDecision === 'ACCEPTED' ? 'OFFER_ACCEPTED' : offer.issuerDecision === 'DECLINED' ? 'OFFER_DECLINED' : 'OFFER_AWAITING';
}

/** Granted by an admin only. */
export function Verified({ when: verified }) {
  if (!verified) return null;
  return (
    <span className="inline-flex items-center gap-1 rounded-sm border border-good bg-good-bg px-1.5 text-xs font-medium whitespace-nowrap text-good">
      <span aria-hidden="true">✓</span>Verified
    </span>
  );
}

/** A link typed by a user. Only http(s) becomes clickable; anything else stays text. */
export function Website({ url }) {
  if (!url) return null;
  if (!/^https?:\/\//i.test(url)) return <span className="break-all">{url}</span>;
  return <a href={url} target="_blank" rel="noopener noreferrer nofollow" className="break-all text-brand underline">{url}</a>;
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
const AS_IS = new Set(['USD', 'EUR', 'GBP', 'CHF', 'JPY', 'INR', 'SGD', 'AUD', 'CAD', 'AED', 'VC', 'PE', 'MEA', 'LATAM']);
export const words = (v) =>
  AS_IS.has(v) ? v : v.toLowerCase().replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase())
    .replace(/\bvc\b/i, 'VC').replace('Series b plus', 'Series B+').replace('Series a', 'Series A')
    .replace('Co lead', 'Co-lead').replace('Follow on', 'Follow-on');

export const control = 'mt-1 min-h-11 w-full rounded-sm border border-ink/30 bg-sheet px-3';

export function Field({ label, children, wide }) {
  return (
    <label className={`block ${wide ? 'sm:col-span-2' : ''}`}>
      <span className="text-sm font-medium">{label}</span>
      {children}
    </label>
  );
}

export function Select({ label, value, onChange, options }) {
  return (
    <Field label={label}>
      <select className={control} value={value} onChange={onChange}>
        {options.map((o) => <option key={o} value={o}>{words(o)}</option>)}
      </select>
    </Field>
  );
}

export const SECTORS = ['TECHNOLOGY', 'FINTECH', 'HEALTHCARE', 'ENERGY', 'REAL_ESTATE', 'CONSUMER', 'INDUSTRIALS'];
export const REGIONS = ['NORTH_AMERICA', 'EUROPE', 'ASIA_PACIFIC', 'LATAM', 'MEA'];
export const INSTRUMENTS = ['EQUITY', 'DEBT', 'CONVERTIBLE', 'MEZZANINE'];
export const RISKS = ['LOW', 'MEDIUM', 'HIGH'];

export const CURRENCIES = ['USD', 'EUR', 'GBP', 'CHF', 'JPY', 'INR', 'SGD', 'AUD', 'CAD', 'AED'];
export const STAGES = ['PRE_SEED', 'SEED', 'SERIES_A', 'SERIES_B_PLUS', 'GROWTH', 'MIDDLE_MARKET'];
export const PARTICIPATION_ROLES = ['LEAD', 'CO_LEAD', 'FOLLOW_ON'];
export const TEAM_SIZES = ['1-10', '11-50', '51-200', '201-1000', '1000+'];
export const INVESTOR_TYPES = ['VC', 'PE', 'PRIVATE_CREDIT', 'FAMILY_OFFICE', 'CORPORATE_VC', 'ANGEL_SYNDICATE'];

/** Empty text becomes "not given" (null); otherwise a number. */
export const number = (v) => (v === '' || v == null ? null : Number(v));

export function Input({ label, wide, hint, ...props }) {
  return (
    <Field label={label} wide={wide}>
      <input {...props} className={`${control} ${props.type === 'number' ? 'font-mono' : ''}`} />
      {hint && <span className="text-xs text-soft">{hint}</span>}
    </Field>
  );
}

export function Section({ title, children }) {
  return (
    <fieldset className="grid gap-4 border-t border-rule pt-4 sm:col-span-2 sm:grid-cols-2">
      <legend className="pr-3 text-lg font-semibold">{title}</legend>
      {children}
    </fieldset>
  );
}
