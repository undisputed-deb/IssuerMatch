const PARTS = [
  ['mandateFit', 'Mandate', 25], ['ticketFit', 'Ticket', 25], ['regionFit', 'Region', 15], ['riskFit', 'Risk', 10],
  ['trackRecord', 'Track record', 10], ['thesisAlignment', 'Thesis', 15],
];

/** Points per criterion, computed by the server. Each bar has its number beside it, so colour is never the only cue. */
export function Breakdown({ breakdown }) {
  if (!breakdown) return null;
  return (
    <dl className="grid grid-cols-[7.5rem_1fr_4.5rem] items-center gap-x-3 gap-y-1 text-sm">
      {PARTS.map(([key, label, max]) => (
        <div key={key} className="contents">
          <dt className="text-soft">{label}</dt>
          <dd aria-hidden="true" className="h-1.5 bg-rule/60">
            <div className="h-full origin-left bg-brand" style={{ transform: `scaleX(${Math.min(1, Number(breakdown[key]) / max)})` }} />
          </dd>
          <dd className="text-right font-mono">{Number(breakdown[key]).toFixed(1)}/{max}</dd>
        </div>
      ))}
    </dl>
  );
}
