-- Phase 13: what version is running where.
--
-- The first fact in this system that is neither a declaration nor a probe
-- result. See docs/adr/0017-deployments-are-reported-not-discovered.md for why
-- the deploying pipeline reports this rather than OpsAtlas inferring it, and
-- for what it deliberately cannot answer.
--
-- Rows, not counters. ADR 0009's rule is about probe results, whose volume is
-- set by an interval this system chooses; a deployment happens when a human or
-- a pipeline ships, so the table grows with release cadence and not with
-- anything OpsAtlas controls. It is platform metadata, which CLAUDE.md section 6
-- says PostgreSQL is the system of record for.

CREATE TABLE deployment (
    id              uuid        PRIMARY KEY,
    org_id          uuid        NOT NULL REFERENCES organization (id) ON DELETE RESTRICT,
    environment_id  uuid        NOT NULL,

    -- Free text on purpose. A version is "1.4.2", "0.1.0", a date stamp or a
    -- branch name depending on whose pipeline is talking, and a format this
    -- table enforced would reject a truthful report from a real deploy.
    version         text        NOT NULL,

    -- Null is honest: not every deploy is from a git commit, and a pipeline
    -- that cannot name one must still be able to report the deploy.
    commit_sha      text        NULL,

    -- Who or what shipped it. "ci:converge", "ci:github-actions", a person's
    -- handle. Never inferred - the reporter says, or it stays null.
    deployed_by     text        NULL,

    -- When the deploy happened, which is not when this row was written. A
    -- pipeline reporting late must be able to state the real time, or "in place
    -- for" becomes a measurement of how long ago OpsAtlas was told.
    deployed_at     timestamptz NOT NULL,

    -- What a retried POST collapses on. CLAUDE.md section 9 requires idempotency
    -- keys for ingestion, and a deploy notification retried after a timeout must
    -- not become two deployments and a fictitious rollback.
    idempotency_key text        NOT NULL,

    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT deployment_version_present CHECK (length(btrim(version)) > 0),
    CONSTRAINT deployment_version_length CHECK (length(version) <= 200),
    -- Permissive on case and length so an abbreviated SHA is acceptable, strict
    -- on shape so a sentence cannot end up in a column a UI renders as a commit.
    CONSTRAINT deployment_commit_format CHECK (commit_sha IS NULL OR commit_sha ~ '^[0-9a-fA-F]{7,40}$'),
    CONSTRAINT deployment_deployed_by_length CHECK (deployed_by IS NULL OR length(deployed_by) <= 120),
    CONSTRAINT deployment_idempotency_length CHECK (length(idempotency_key) BETWEEN 1 AND 200),

    -- The idempotency guarantee, enforced here rather than by the application
    -- reading before writing - which races with itself under a retry storm,
    -- exactly when it matters.
    CONSTRAINT deployment_idempotent_per_environment UNIQUE (org_id, environment_id, idempotency_key),

    -- Composite, for the same reason as every other one in this schema: a
    -- deployment cannot belong to an environment in a different organization,
    -- and the database refuses it rather than trusting the application to have
    -- filtered correctly. CASCADE so deleting a service takes its deploy
    -- history with it, like its observations and policy results.
    CONSTRAINT deployment_environment_same_org FOREIGN KEY (org_id, environment_id)
        REFERENCES environment (org_id, id) ON DELETE CASCADE
);

-- The only query that matters on the hot path: the most recent deployment for
-- each environment of one service. DESC because every read wants the latest.
CREATE INDEX deployment_org_environment_time_idx
    ON deployment (org_id, environment_id, deployed_at DESC);

COMMENT ON TABLE deployment IS
    'What was deployed where, as reported by the deploying pipeline. OpsAtlas does not discover this and does not verify it: a row says somebody claimed a version went out, not that it is running. Drift detection needs an observed version, which needs a service to expose one - see ADR 0017.';
