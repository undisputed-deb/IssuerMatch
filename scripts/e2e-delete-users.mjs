// Deletes the accounts browser tests sign up (e2e-*@issuermatch.test). Used by e2e-cleanup.sh.
const url = process.env.SUPABASE_URL;
const key = process.env.SUPABASE_ADMIN_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_SECRET_KEY;
const TEST_ACCOUNT = /^e2e-.*@issuermatch\.test$/;

if (!url || !key) {
  console.log('cleanup: no Supabase URL or admin key in the environment, no accounts removed');
  process.exit(0);
}

const headers = { apikey: key, Authorization: `Bearer ${key}` };
const found = [];
for (let page = 1; ; page++) { // collect first: deleting while paging would skip users
  const res = await fetch(`${url}/auth/v1/admin/users?page=${page}&per_page=200`, { headers });
  if (!res.ok) throw new Error(`cleanup: listing users failed (${res.status})`);
  const { users } = await res.json();
  found.push(...users.filter((u) => TEST_ACCOUNT.test(u.email ?? '')));
  if (users.length < 200) break;
}
let failed = 0;
for (const user of found) {
  const res = await fetch(`${url}/auth/v1/admin/users/${user.id}`, { method: 'DELETE', headers });
  if (!res.ok) failed++;
}
console.log(`cleanup: ${found.length - failed} test account(s) removed`);
if (failed) throw new Error(`cleanup: ${failed} account(s) could not be removed`);
