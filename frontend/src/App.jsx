import { useEffect, useState } from 'react';
import { currentUser, setSignedOutHandler, signOut } from './api.js';
import Login from './pages/Login.jsx';
import Needs from './pages/Needs.jsx';
import NewNeed from './pages/NewNeed.jsx';
import RunDetail from './pages/RunDetail.jsx';
import Runs from './pages/Runs.jsx';

/** Tiny hash router: #/runs, #/runs/<id>, #/needs, #/new. */
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

export default function App() {
  const [user, setUser] = useState(currentUser);
  const [page, id] = useRoute();
  useEffect(() => setSignedOutHandler(() => setUser(null)), []);

  if (!user) return <Login onSignedIn={setUser} />;

  const staff = user.roles.some((r) => r === 'ANALYST' || r === 'ADMIN');
  const submitter = user.roles.some((r) => r === 'ISSUER' || r === 'ADMIN');
  const tabs = [
    staff && ['runs', 'Match runs'],
    (staff || submitter) && ['needs', 'Funding needs'],
    submitter && ['new', 'Submit a need'],
  ].filter(Boolean);
  const active = tabs.some(([key]) => key === page) ? page : tabs[0]?.[0];

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
      <main id="main" tabIndex={-1} className="mx-auto max-w-6xl px-4 py-8">
        {!active && <p>Your account has no role yet. Ask an administrator to give you one.</p>}
        {active === 'runs' && (id ? <RunDetail id={id} /> : <Runs />)}
        {active === 'needs' && <Needs />}
        {active === 'new' && <NewNeed />}
      </main>
      <footer className="mx-auto max-w-6xl px-4 pb-8 text-sm text-soft">Synthetic data. Not affiliated with any company.</footer>
    </div>
  );
}
