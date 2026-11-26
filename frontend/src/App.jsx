import { useEffect, useState } from 'react';
import { api, currentUser, setSignedOutHandler, signOut } from './api.js';
import { ErrorBox, Loading, useLoad } from './ui.jsx';
import Company from './pages/Company.jsx';
import DeadLetters from './pages/DeadLetters.jsx';
import Login from './pages/Login.jsx';
import Needs from './pages/Needs.jsx';
import NewNeed from './pages/NewNeed.jsx';
import Profile from './pages/Profile.jsx';
import Reconciliation from './pages/Reconciliation.jsx';
import RiskReview from './pages/RiskReview.jsx';
import RunDetail from './pages/RunDetail.jsx';
import Runs from './pages/Runs.jsx';

/** Tiny hash router: #/runs, #/runs/<id>, #/needs, ... */
function useRoute() {
  const read = () => window.location.hash.replace(/^#\/?/, '').split('/');
  const [route, setRoute] = useState(read);
  useEffect(() => {
    const onChange = () => setRoute(read());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}

/**
 * Which area a user gets, decided strictly from the token's roles: staff first, then issuer, then investor.
 * This only chooses what to show. The server enforces every permission.
 */
function areaOf(roles) {
  if (roles.includes('ADMIN') || roles.includes('ANALYST')) return 'staff';
  if (roles.includes('ISSUER')) return 'issuer';
  if (roles.includes('INVESTOR')) return 'investor';
  return null;
}

export default function App() {
  const [user, setUser] = useState(currentUser);
  useEffect(() => setSignedOutHandler(() => setUser(null)), []);
  if (!user) return <Login onSignedIn={(u) => { window.location.hash = ''; setUser(u); }} />;
  const area = areaOf(user.roles);
  if (area === 'staff') return <StaffArea user={user} />;
  if (area === 'issuer') return <IssuerArea user={user} />;
  if (area === 'investor') return <InvestorArea user={user} />;
  return (
    <Shell user={user} tabs={[]}>
      <p>Your account has no role yet. Ask an administrator to give you one.</p>
    </Shell>
  );
}

function StaffArea({ user }) {
  const [page, id] = useRoute();
  const admin = user.roles.includes('ADMIN');
  const tabs = [
    ['runs', 'Match runs'],
    ['needs', 'Funding needs'],
    ['risk', 'Risk review'],
    admin && ['dead-letters', 'Dead letters'],
    admin && ['reconciliation', 'Reconciliation'],
    admin && ['new', 'Submit a need'],
  ].filter(Boolean);
  const active = tabs.some(([key]) => key === page) ? page : 'runs';
  return (
    <Shell user={user} tabs={tabs} active={active}>
      {active === 'runs' && (id ? <RunDetail id={id} /> : <Runs />)}
      {active === 'needs' && <Needs canRematch />}
      {active === 'risk' && <RiskReview />}
      {active === 'dead-letters' && <DeadLetters />}
      {active === 'reconciliation' && <Reconciliation />}
      {active === 'new' && <NewNeed />}
    </Shell>
  );
}

function IssuerArea({ user }) {
  const [page] = useRoute();
  const tabs = [['company', 'My dashboard'], ['new', 'Submit a need']];
  const active = page === 'new' ? 'new' : 'company';
  return (
    <Shell user={user} tabs={tabs} active={active}>
      {active === 'company' && <Company />}
      {active === 'new' && <NewNeed />}
    </Shell>
  );
}

function InvestorArea({ user }) {
  const [page] = useRoute();
  // null = no profile yet: the investor goes to onboarding before anything else
  const profile = useLoad(async () => {
    try {
      return { value: await api.myProfile() };
    } catch (e) {
      if (e.status === 404) return { value: null };
      throw e;
    }
  }, []);
  const [saved, setSaved] = useState(null);
  const current = saved ?? profile.data?.value ?? null;
  const complete = Boolean(current);
  const tabs = complete ? [['deals', 'Matched deals'], ['profile', 'My profile']] : [['profile', 'Set up profile']];
  const active = complete && page !== 'profile' ? 'deals' : 'profile';
  return (
    <Shell user={user} tabs={tabs} active={active}>
      {profile.loading && <Loading />}
      <ErrorBox error={profile.error} onRetry={profile.reload} />
      {profile.data && active === 'profile' && <Profile profile={current} onSaved={(p) => { setSaved(p); window.location.hash = '/profile'; }} />}
      {profile.data && active === 'deals' && (
        <Needs title="Matched deals" empty="No deals yet. A deal appears here after our review team approves a match with your firm." />
      )}
    </Shell>
  );
}

function Shell({ user, tabs, active, children }) {
  return (
    <div className="min-h-screen">
      <a href="#main" onClick={(e) => { e.preventDefault(); document.getElementById('main').focus(); }}
        className="sr-only focus:not-sr-only focus:absolute focus:m-2 focus:bg-sheet focus:p-2">
        Skip to content
      </a>
      <header className="bg-ink text-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-8 gap-y-1 px-4 py-2">
          <p className="font-mono text-lg tracking-tight">Issuer<span className="text-amber-300">Match</span></p>
          <nav aria-label="Main" className="flex flex-wrap">
            {tabs.map(([key, label]) => (
              <a key={key} href={`#/${key}`} aria-current={active === key ? 'page' : undefined}
                className={`flex min-h-11 items-center border-b-4 px-3 text-sm font-medium transition-colors ${
                  active === key ? 'border-amber-300 text-white' : 'border-transparent text-white/70 hover:text-white'}`}>
                {label}
              </a>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm text-white/80">
            <span className="hidden sm:inline">{user.email}</span>
            <span className="rounded-sm border border-white/30 px-1.5 font-mono text-xs">{user.roles.join(' ') || 'NO ROLE'}</span>
            <button type="button" onClick={signOut} className="min-h-11 px-2 underline hover:text-white">Sign out</button>
          </div>
        </div>
      </header>
      <main id="main" tabIndex={-1} className="mx-auto max-w-6xl px-4 py-8">{children}</main>
      <footer className="mx-auto max-w-6xl px-4 pb-8 text-sm text-soft">Synthetic data. Not affiliated with any company.</footer>
    </div>
  );
}
