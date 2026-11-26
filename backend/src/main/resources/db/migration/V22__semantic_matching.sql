-- Semantic thesis search, structured scores, deal briefs and degraded-mode bookkeeping.
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE investors
    ADD COLUMN thesis_embedding     vector(768),
    ADD COLUMN thesis_embedded_hash text;   -- md5 of the text that was embedded: changed thesis = embed again
-- Approximate nearest neighbour search by cosine distance.
CREATE INDEX idx_investors_thesis ON investors USING hnsw (thesis_embedding vector_cosine_ops);

ALTER TABLE funding_needs ADD COLUMN embedding vector(768);  -- computed once, on the first match

ALTER TABLE match_runs
    ADD COLUMN execution_mode  text CHECK (execution_mode IN ('AGENT','DEGRADED_DETERMINISTIC')),
    ADD COLUMN degraded_reason text;

ALTER TABLE match_results
    ADD COLUMN score_breakdown               jsonb,
    ADD COLUMN deal_brief                    text  CHECK (char_length(deal_brief) <= 2000),  -- shown to that investor only
    ADD COLUMN suggested_diligence_questions jsonb NOT NULL DEFAULT '[]'::jsonb
                                             CHECK (jsonb_typeof(suggested_diligence_questions) = 'array');

-- Tokens spent per day, for the budget that switches matching to deterministic mode.
CREATE TABLE llm_daily_usage (
    usage_day date   PRIMARY KEY,
    tokens    bigint NOT NULL DEFAULT 0 CHECK (tokens >= 0)
);
ALTER TABLE llm_daily_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE investors       ENABLE ROW LEVEL SECURITY;
