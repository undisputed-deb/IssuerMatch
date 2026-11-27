// The only file that talks to the network. Everything else calls these functions.
const API = import.meta.env.VITE_API_URL || 'http://localhost:8080';
const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL;
const SUPABASE_KEY = import.meta.env.VITE_SUPABASE_ANON_KEY; // publishable, safe in a browser
const TOKEN_KEY = 'issuermatch.token'; // sessionStorage: gone when the tab closes

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

let onSignedOut = () => {};
export const setSignedOutHandler = (fn) => {
  onSignedOut = fn;
};

/** Reads claims for DISPLAY only (which tabs to show). The server verifies the token and enforces every rule. */
export function currentUser() {
  const token = sessionStorage.getItem(TOKEN_KEY);
  if (!token) return null;
  try {
    const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const claims = JSON.parse(atob(part.padEnd(Math.ceil(part.length / 4) * 4, '=')));
    if (claims.exp * 1000 < Date.now()) return null;
    return { id: claims.sub, email: claims.email, roles: claims.app_metadata?.roles ?? [] };
  } catch {
    return null;
  }
}

export async function signIn(email, password) {
  const res = await send(`${SUPABASE_URL}/auth/v1/token?grant_type=password`, {
    method: 'POST',
    headers: { apikey: SUPABASE_KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok || !body.access_token) {
    throw new ApiError(res.status, 'LOGIN_FAILED', body.msg || body.error_description || 'Login failed');
  }
  sessionStorage.setItem(TOKEN_KEY, body.access_token);
  return currentUser();
}

/** Public signup, then sign in. Only ISSUER and INVESTOR can be chosen; the server enforces it. */
export async function signUp({ fullName, email, password, role, inviteToken }) {
  const res = await send(`${API}/auth/signup`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fullName, email, password, role, inviteToken }),
  });
  if (!res.ok) {
    const data = await res.json().catch(() => null);
    const wait = res.headers.get('Retry-After');
    const message = data?.error?.message || `Signup failed (${res.status})`;
    throw new ApiError(res.status, data?.error?.code || 'ERROR', wait ? `${message}. Try again in ${wait}s.` : message);
  }
  return signIn(email, password);
}

export function signOut() {
  sessionStorage.removeItem(TOKEN_KEY);
  onSignedOut();
}

async function send(url, options) {
  try {
    return await fetch(url, options);
  } catch {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server. Is it running?');
  }
}

/** For the invitation page, before anyone is signed in. A stale token is deliberately not sent. */
async function publicGet(path) {
  const res = await send(API + path, {});
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, data?.error?.code || 'ERROR', data?.error?.message || `Request failed (${res.status})`);
  return data;
}

// `decision`: a body to accept even with an error status (a blocked offer is a stored answer, sent as 422)
async function request(path, { method = 'GET', body, headers = {}, decision } = {}) {
  const res = await send(API + path, {
    method,
    headers: {
      Authorization: `Bearer ${sessionStorage.getItem(TOKEN_KEY)}`,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401) {
    signOut();
    throw new ApiError(401, 'UNAUTHORIZED', 'Your session ended. Sign in again.');
  }
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok && !decision?.(data)) {
    const wait = res.headers.get('Retry-After');
    const message = data?.error?.message || `Request failed (${res.status})`;
    throw new ApiError(res.status, data?.error?.code || 'ERROR', wait ? `${message}. Try again in ${wait}s.` : message);
  }
  return data;
}

export const api = {
  issuers: () => request('/issuers?size=100&sort=name,asc'),
  createIssuer: (company) => request('/issuers', { method: 'POST', body: company }),
  updateIssuer: (id, company) => request(`/issuers/${id}`, { method: 'PUT', body: company }),
  myProfile: () => request('/investors/me'),
  saveProfile: (profile) => request('/investors/me', { method: 'PUT', body: profile }),
  commitments: (status) => request(`/commitments?size=50&status=${status}`),
  commitment: (id) => request(`/commitments/${id}`),
  reviewCommitment: (id, approve) => request(`/commitments/${id}/${approve ? 'approve' : 'reject'}`, { method: 'POST' }),
  newestIssuers: () => request('/issuers?size=100&sort=createdAt,desc'),
  newestInvestors: () => request('/investors?size=100&sort=createdAt,desc'),
  verify: (kind, id, verified) => request(`/admin/${kind}/${id}/verify`, { method: 'PATCH', body: { verified } }),
  team: () => request('/investors/me/team'),
  invite: (email, orgRole) => request('/investors/me/invites', { method: 'POST', body: { email, orgRole } }),
  revokeInvite: (id) => request(`/investors/me/invites/${id}`, { method: 'DELETE' }),
  changeRole: (userId, orgRole) => request(`/investors/me/members/${userId}`, { method: 'PATCH', body: { orgRole } }),
  removeMember: (userId) => request(`/investors/me/members/${userId}`, { method: 'DELETE' }),
  invitePreview: (token) => publicGet(`/investor-invites/${encodeURIComponent(token)}/preview`),
  acceptInvite: (token) => request(`/investor-invites/${encodeURIComponent(token)}/accept`, { method: 'POST' }),
  deadLetters: () => request('/admin/dead-letters?size=50&status=PENDING'),
  replayDeadLetter: (id) => request(`/admin/dead-letters/${id}/replay`, { method: 'POST' }),
  reconciliationLatest: () => request('/admin/reconciliation/latest'),
  reconciliationRun: () => request('/admin/reconciliation/run', { method: 'POST' }),
  needs: () => request('/funding-needs?size=100&sort=createdAt,desc'),
  need: (id) => request(`/funding-needs/${id}`),
  submitNeed: (need) => request('/funding-needs', { method: 'POST', body: need }),
  rematch: (id) => request(`/funding-needs/${id}/match`, { method: 'POST' }),
  runs: (page = 0) => request(`/match-runs?size=20&page=${page}`),
  investorMatches: () => request('/investor/matches'),
  investorOffers: () => request('/investor/offers'),
  investorQuestions: () => request('/investor/questions'),
  issuerQuestionCount: () => request('/issuer/questions/unanswered-count'),
  submitOffer: (offer, key) =>
    request('/commitments', { method: 'POST', body: offer, headers: { 'Idempotency-Key': key }, decision: (d) => d?.status === 'REJECTED' }),
  pass: (matchResultId, reason) => request(`/investor/matches/${matchResultId}/pass`, { method: 'POST', body: { reason } }),
  offers: (needId) => request(`/funding-needs/${needId}/offers`),
  decideOffer: (commitmentId, accept) =>
    request(`/commitments/${commitmentId}/issuer-${accept ? 'accept' : 'decline'}`, { method: 'POST' }),
  questions: (needId) => request(`/funding-needs/${needId}/questions`),
  ask: (needId, question) => request(`/funding-needs/${needId}/questions`, { method: 'POST', body: { question } }),
  answer: (needId, id, answer) => request(`/funding-needs/${needId}/questions/${id}/answer`, { method: 'POST', body: { answer } }),
  run: (id) => request(`/match-runs/${id}`),
  results: (id) => request(`/match-runs/${id}/results`),
  review: (id, approve) => request(`/match-runs/${id}/${approve ? 'approve' : 'reject'}`, { method: 'POST' }),
};
