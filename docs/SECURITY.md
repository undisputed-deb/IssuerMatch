# Security analysis and controls

I wrote the analysis first and then built only what protects this application. All data is synthetic.

## What the app holds and who attacks it
An API that takes funding requests from issuers, lets an LLM agent rank confidential investors, and records money offers.
The realistic attackers are: a logged-in issuer who wants another company's data or the investor list, an investor who
wants unmatched deals or a rival's offers and questions, a stranger who signs up in public, and whoever writes the free
text that reaches the language model (issuers in funding needs, investors in their thesis).

## Four questions
| Question | Answer | Why for this app |
|---|---|---|
| Rate limiting? | Yes | Each funding need starts a paid Gemini call. Without a limit one script spends the budget and fills the queue. Login-free endpoints also need a per-IP cap. |
| Input validation? | Yes | Free text flows into the database and into an LLM prompt. Amounts drive capacity and risk rules. |
| Authentication? | Yes (built) | Investor data is confidential and commitments move money-like records. |
| Output handling? | Yes | The model writes explanations that a browser renders. |

## OWASP Top 10 (2021) mapping
| Item | Applies | Control here |
|---|---|---|
| A01 Broken access control | Yes, highest | Default deny, role gates, ownership checks that answer 404, per-user idempotency keys, four-eyes review. |
| A02 Cryptographic failures | Partly | TLS to Supabase. The app stores no passwords. HSTS on HTTPS responses. |
| A03 Injection | Yes | Parameterised SQL only, Bean Validation, strict JSON, no NUL bytes. |
| A04 Insecure design | Yes | Human approval, read-only agent tools, idempotency, row locks, fail-closed risk engine. |
| A05 Security misconfiguration | Yes | Explicit CORS origin, security headers, startup fails without secrets, Actuator locked to admins. |
| A06 Vulnerable components | Yes | SBOM scan in CI that fails on findings, Dependabot. |
| A07 Authentication failures | Delegated | Supabase handles login. This API verifies signature, issuer, audience, expiry and algorithm. |
| A08 Integrity failures | Yes | Kafka payload validation, outbox, dead-letter replay limited to known topics. |
| A09 Logging and monitoring failures | Yes | Audit log, correlation ids, no secrets or funding text in logs, reconciliation. |
| A10 SSRF | No | The server calls only fixed hosts from configuration. No user input becomes a URL. |

## OWASP Top 10 for LLM applications (2025) mapping
| Item | Applies | Control here |
|---|---|---|
| LLM01 Prompt injection | Yes, highest | Untrusted text fenced and escaped, instruction-like content flagged for the reviewer, tool allowlist, need-bound `scoreFit`. |
| LLM02 Sensitive information disclosure | Yes | The model sees only what read-only tools return. Issuers never see rankings, and an investor's brief and questions may not name another investor. |
| LLM03 Supply chain | Yes | Dependency scan and pinned model name. |
| LLM04 Data and model poisoning | No | The app trains nothing. |
| LLM05 Improper output handling | Yes | Strict JSON schema, investors must come from this run's search, explanations with markup or links are rejected, the UI renders plain text. |
| LLM06 Excessive agency | Yes | Five read-only tools, 12-call budget, one deadline, no write path, human approval. |
| LLM07 System prompt leakage | Low | The prompt holds no secrets, so leaking it gives an attacker nothing. |
| LLM08 Vector and embedding weaknesses | Yes | Investors write the text that gets embedded. The search returns it marked untrusted, injection patterns flag the run, and similarity adds at most 15 of 100 points. |
| LLM09 Misinformation | Yes | Scores come from Java. Every explanation cites tool calls that the reviewer can open. |
| LLM10 Unbounded consumption | Yes | Rate limits on the endpoints that start a model call, timeout, circuit breaker and a daily token budget. |

## Controls: attack stopped, reason, resume line
| Control | Attack it stops | Why it matters here | Resume line |
|---|---|---|---|
| Token-bucket rate limits (per IP, per user, stricter for LLM endpoints) | Brute force, scraping, cost exhaustion | Every submitted need costs a model call | "Added per-user and per-IP token-bucket limits, tighter on LLM-triggering endpoints, returning 429 with Retry-After." |
| Request size cap and NUL rejection | Memory exhaustion, database errors from crafted bytes | Free text is accepted from outside parties | "Rejected oversized bodies and invalid characters at the edge." |
| Instruction detection plus fencing | Prompt injection through the funding need | The text reaches an LLM that sees investor data | "Fenced untrusted text, flagged injection attempts for human reviewers and bound agent tools to the run." |
| Output checks on explanations | Stored XSS and link-based exfiltration | Reviewers read model text in a browser | "Rejected model output containing markup or links and rendered it as plain text." |
| CORS allowlist and security headers | Cross-site reads, clickjacking, MIME sniffing | The browser app calls the API with a bearer token | "Locked CORS to one origin and set CSP, HSTS and frame protections." |
| Correlation ids and log hygiene | Untraceable incidents, data leaks through logs | Funding text is confidential | "Traced requests through HTTP and Kafka with correlation ids, keeping tokens and free text out of logs." |
| SBOM scan in CI | Known vulnerable libraries | The first scan found 4 High findings in transitive dependencies | "Added SBOM vulnerability scanning to CI and fixed 4 High findings it found." |

## Secrets
`GEMINI_API_KEY` and the database password come from environment variables (`backend/.env` locally, never committed).
gitleaks runs before each commit and in CI. The frontend receives only the publishable Supabase key.
Public signup needs a server-side Supabase admin key (`SUPABASE_SERVICE_ROLE_KEY`, or `SUPABASE_SECRET_KEY`). That key can
manage every account in the project, so only the signup client reads it, and the client refuses to create staff roles.

## Kafka
Locally the broker listens on 127.0.0.1 without TLS or authentication, which is acceptable for synthetic data on one machine.
In production I would enable TLS for transport, SASL (SCRAM or mTLS) for identity, and per-topic ACLs: only the API writes
`funding-need.submitted` and `commitment.*`, only the matching consumer reads `match.requested`, and only operators read `*.DLT`.
Consumers already treat each message as untrusted: they validate ids, types and amounts, and replay targets an allowlist.

## Not covered
No web application firewall, no multi-factor requirement for admins, no encryption of the funding text at rest beyond what
Supabase provides, and rate limits live in each app instance (a shared store such as Redis would be needed behind a load balancer).
