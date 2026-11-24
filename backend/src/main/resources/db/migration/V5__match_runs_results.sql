-- One match run = one agent execution for one funding need. Results are the ranked investors.

CREATE TABLE match_runs (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    need_id     uuid        NOT NULL REFERENCES funding_needs (id) ON DELETE CASCADE,
    event_id    uuid        NOT NULL,  -- Kafka event that triggered this run (idempotency)
    status      text        NOT NULL DEFAULT 'REQUESTED'
                CHECK (status IN ('REQUESTED','RUNNING','PENDING_REVIEW','APPROVED','REJECTED','FAILED')),
    tokens_in   integer     CHECK (tokens_in  >= 0),
    tokens_out  integer     CHECK (tokens_out >= 0),
    latency_ms  integer     CHECK (latency_ms >= 0),
    tool_calls  jsonb       NOT NULL DEFAULT '[]'::jsonb,  -- every tool call + result, for explainability
    error       text,
    reviewed_by uuid,
    reviewed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Same event delivered twice cannot create a second run.
    CONSTRAINT uq_run_event UNIQUE (need_id, event_id)
);
-- "Run history for this need"
CREATE INDEX idx_runs_need ON match_runs (need_id, created_at DESC);

CREATE TABLE match_results (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id      uuid          NOT NULL REFERENCES match_runs (id) ON DELETE CASCADE,
    investor_id uuid          NOT NULL REFERENCES investors (id),
    rank        integer       NOT NULL CHECK (rank > 0),
    score       numeric(5,2)  NOT NULL CHECK (score BETWEEN 0 AND 100),  -- computed by Java, never by the LLM
    explanation text          NOT NULL,
    cited_tools jsonb         NOT NULL DEFAULT '[]'::jsonb,  -- which tool results the explanation used
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_result_investor UNIQUE (run_id, investor_id),
    CONSTRAINT uq_result_rank     UNIQUE (run_id, rank)
);
-- uq_result_investor / uq_result_rank already index run_id as their leading column ("results of run X, ordered by rank").
-- FK to investors: needed when deleting or looking up by investor.
CREATE INDEX idx_results_investor ON match_results (investor_id);

ALTER TABLE match_runs    ENABLE ROW LEVEL SECURITY;
ALTER TABLE match_results ENABLE ROW LEVEL SECURITY;
