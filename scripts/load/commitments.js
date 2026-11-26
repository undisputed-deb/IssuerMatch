// Load test for POST /commitments: idempotency, row locks, five risk rules, audit and outbox on every request.
// Run through scripts/load/run.sh, which prepares the data and a token.
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const pairs = new SharedArray('pairs', () => JSON.parse(open('./pairs.json')));
const accepted = new Counter('commitments_accepted');
const flagged = new Counter('commitments_flagged');
const blocked = new Counter('commitments_blocked');
const base = __ENV.BASE_URL || 'http://localhost:8080';
const vus = Number(__ENV.VUS || 50);

export const options = {
  scenarios: {
    commitments: { executor: 'constant-vus', vus: vus, duration: __ENV.DURATION || '60s' },
  },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<500'] },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// 201 accepted, 202 flagged, 422 blocked by a rule: all three are correct answers.
http.setResponseCallback(http.expectedStatuses(201, 202, 422));

export default function () {
  const pair = pairs[Math.floor(Math.random() * pairs.length)];
  const amount = 1000 + Math.floor(Math.random() * 99000);
  const res = http.post(`${base}/commitments`,
    JSON.stringify({ needId: pair.needId, investorId: pair.investorId, amount }),
    { headers: {
      Authorization: `Bearer ${__ENV.TOKEN}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': `k6-${__ENV.RUN_ID}-${__VU}-${__ITER}`,
    } });
  check(res, { 'decided (201/202/422)': (r) => [201, 202, 422].includes(r.status) });
  if (res.status === 201) accepted.add(1);
  else if (res.status === 202) flagged.add(1);
  else if (res.status === 422) blocked.add(1);
}
