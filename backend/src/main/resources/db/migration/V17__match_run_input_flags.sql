-- Instruction-like content detected in the untrusted funding text. Shown to the reviewer; it never blocks by itself.
ALTER TABLE match_runs ADD COLUMN input_flags text[] NOT NULL DEFAULT '{}';
