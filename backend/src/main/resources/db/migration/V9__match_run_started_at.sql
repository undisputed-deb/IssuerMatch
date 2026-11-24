-- When a run was claimed. Lets a reaper find runs stuck in RUNNING after a crash.
ALTER TABLE match_runs ADD COLUMN started_at timestamptz;

-- Tiny partial index: only RUNNING rows are ever scanned by the reaper.
CREATE INDEX idx_runs_running ON match_runs (started_at) WHERE status = 'RUNNING';
