# Commitment risk engine

Every commitment, recorded by staff or offered by an investor, goes through the rules below before it is accepted.
Investors receive the resulting status without the rule reasons. Money thresholds are in USD; amounts in other currencies
are converted with the fixed synthetic rates. Each rule answers PASS, FLAG or BLOCK with a reason.
All results are stored in `risk_decisions`, together with the thresholds that were in force at that moment.

| Overall result | Commitment status | HTTP | Capacity |
|---|---|---|---|
| every rule PASS | `ACCEPTED` | 201 | held |
| any FLAG, no BLOCK | `PENDING_REVIEW` | 202 | held until reviewed |
| any BLOCK | `REJECTED` | 422 (the stored rejection, with reasons) | not held |

A retried request (same `Idempotency-Key`) returns the same stored answer, whichever of the three it was.

## Rules (defaults)
| Rule id | PASS | FLAG | BLOCK |
|---|---|---|---|
| `ticket-range` | amount inside a mandate range for this sector and region | outside the range by up to `blockOutsidePct` (50) %, or no mandate on file | further outside than that |
| `concentration` | investor's total in this issuer (existing + this) up to `flagAbove` (5,000,000) | above `flagAbove` | above `blockAbove` (10,000,000) |
| `velocity` | fewer than `flagCount` (5) commitments in `windowMinutes` (10), counting this one | at `flagCount` or more | at `blockCount` (10) or more |
| `capacity` | amount up to `flagPctOfRemaining` (90) % of what the raise still has | above that share | above the remaining capacity |
| `duplicate` | no identical commitment (same investor, need, amount) in `windowMinutes` (60) | `flagCount` (1) or more | `blockCount` (3) or more |

A rule that crashes counts as FLAG (fail closed), never PASS.

## Changing the rules at runtime (ADMIN only)
- `GET /admin/risk-rules` lists every rule with its defaults and effective settings.
- `PUT /admin/risk-rules/{id}` with `{"enabled": true, "params": {"blockOutsidePct": 80}}`.
  `params` holds only the overrides. Unknown keys, negative numbers and flag-above-block are refused with 400.
  The change applies to the next commitment. It is audited (`RISK_RULE_UPDATED`).
- Settings live in `risk_rule_config`. No row means the rule's built-in defaults. Only overrides are stored, so a changed built-in default in a later release is not frozen out.

## Adding a rule
Write a class that implements `RiskRule` and mark it `@Component`. That is all: the engine runs it, `risk_decisions` stores it,
and the admin API can tune it. `RiskEngineTest` does exactly this with a deliberately broken rule.

## Review of flagged commitments
`POST /commitments/{id}/approve` or `/reject` (ANALYST or ADMIN).
- Four eyes: whoever recorded the commitment cannot review it (403).
- Approve: becomes `ACCEPTED`. Reject: becomes `REJECTED` and the held capacity returns to the raise.
- Only `PENDING_REVIEW` can be reviewed, once (otherwise 409).

## The issuer's answer
`POST /commitments/{id}/issuer-accept` or `/issuer-decline`, by the owner of the company raising the money.
- Only an `ACCEPTED` commitment the issuer has not answered (otherwise 409). Anyone else's commitment is 404.
- The status stays `ACCEPTED`; the answer is stored in `issuer_decision`.
- Decline locks the need, returns the capacity, records `COMMITMENT_ISSUER_DECLINED` and publishes `commitment.declined`
  in one transaction. A declined commitment no longer counts toward concentration exposure. It still counts toward
  velocity and duplicate limits, because those measure how often an investor submits.

## Concurrency
Each commitment locks two rows before any rule runs: the funding need (`FOR UPDATE`), then the investor (`FOR NO KEY UPDATE`).
Requests for the same need queue, and so do requests from the same investor, so every rule judges consistent numbers.
The order is always need then investor, so two requests cannot deadlock. A conditional capacity `UPDATE` stays as a backstop,
and the table has `CHECK remaining_capacity >= 0`.
`CommitmentVelocityRaceTest` fires 15 parallel requests from one investor at 15 needs. Without the investor lock 10 of them
passed cleanly; with it exactly 4 pass, 5 are flagged and 6 are blocked.
