import { useState } from 'react';
import { api } from '../api.js';
import { Button, ErrorBox, Field, Loading, PageTitle, Verified, Website, control, useLoad, words } from '../ui.jsx';

/** Admin only: grant or remove the Verified badge. Newest accounts first. */
export default function Verification() {
  const [search, setSearch] = useState('');
  return (
    <>
      <PageTitle title="Verification">A badge is removed on its own when the name or website changes.</PageTitle>
      <div className="mb-6 max-w-sm">
        <Field label="Filter by name">
          <input type="search" className={control} value={search} onChange={(e) => setSearch(e.target.value)} />
        </Field>
      </div>
      <Accounts title="Companies" kind="issuers" load={api.newestIssuers} search={search}
        website={(c) => c.profile.websiteUrl} detail={(c) => words(c.sector)} />
      <Accounts title="Investor firms" kind="investors" load={api.newestInvestors} search={search}
        website={(f) => f.websiteUrl} detail={(f) => words(f.investorType)} />
    </>
  );
}

function Accounts({ title, kind, load, search, website, detail }) {
  const list = useLoad(load, []);
  const [state, setState] = useState({ busy: null, error: null });
  const shown = list.data?.items.filter((x) => x.name.toLowerCase().includes(search.trim().toLowerCase()));

  async function toggle(item) {
    setState({ busy: item.id, error: null });
    try {
      await api.verify(kind, item.id, !item.verified);
      await list.reload();
      setState({ busy: null, error: null });
    } catch (error) {
      setState({ busy: null, error });
    }
  }

  return (
    <section aria-label={title} className="mb-10">
      <h2 className="mb-2 text-lg font-semibold">{title}</h2>
      <ErrorBox error={list.error} onRetry={list.reload} />
      <ErrorBox error={state.error} />
      {list.loading && <Loading label={`Loading ${title.toLowerCase()}`} />}
      {shown?.length === 0 && <p className="text-sm text-soft">Nothing matches.</p>}
      <ul className="divide-y divide-rule border-y border-rule bg-sheet empty:hidden">
        {shown?.map((x) => (
          <li key={x.id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-2">
            <div className="min-w-0">
              <p className="flex flex-wrap items-center gap-2">
                <span className="font-medium break-words">{x.name}</span>
                <Verified when={x.verified} />
                <span className="text-sm text-soft">{detail(x)}</span>
              </p>
              <p className="text-sm">{website(x) ? <Website url={website(x)} /> : <span className="text-soft">No website given</span>}</p>
            </div>
            <Button tone={x.verified ? 'danger' : 'plain'} busy={state.busy === x.id} disabled={state.busy !== null}
              onClick={() => toggle(x)} aria-label={`${x.verified ? 'Unverify' : 'Verify'} ${x.name}`}>
              {x.verified ? 'Unverify' : 'Verify'}
            </Button>
          </li>
        ))}
      </ul>
      {list.data?.total > list.data?.items.length && (
        <p className="mt-2 text-sm text-soft">Showing the newest {list.data.items.length} of {list.data.total}.</p>
      )}
    </section>
  );
}
